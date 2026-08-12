package top.ellan.mahjong.runtime.loading;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Inserts cooperative execution and allocation checks into every loaded rule class. */
final class RulePackClassInstrumenter {
    private static final String BUDGET = "top/ellan/mahjong/spi/RuleExecutionBudget";

    private RulePackClassInstrumenter() {}

    static byte[] instrument(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassNode type = new ClassNode(Opcodes.ASM9);
        reader.accept(type, 0);
        for (MethodNode method : type.methods) {
            instrument(method);
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static void instrument(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || method.instructions.size() == 0) {
            return;
        }
        AbstractInsnNode[] original = method.instructions.toArray();
        Set<LabelNode> guardedHandlers = new HashSet<>();
        method.tryCatchBlocks.forEach(block -> {
            if (guardedHandlers.add(block.handler)) {
                AbstractInsnNode insertion = firstExecutableAfter(block.handler);
                if (insertion == null) {
                    method.instructions.insert(block.handler, handlerGuard());
                } else {
                    method.instructions.insertBefore(insertion, handlerGuard());
                }
            }
        });
        AbstractInsnNode entry = firstExecutable(original);
        if (entry != null && !method.name.equals("<init>")) {
            method.instructions.insertBefore(entry, checkpoint());
        }
        int pendingConstructions = 0;
        for (AbstractInsnNode instruction : original) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW) {
                pendingConstructions++;
                continue;
            }
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("<init>")) {
                if (pendingConstructions > 0 && --pendingConstructions == 0) {
                    method.instructions.insert(instruction, checkpoint());
                }
                continue;
            }
            // Stack-map frames identify an uninitialized object by the bytecode offset of NEW.
            // Rewriting an argument expression while that value is live can invalidate the offset,
            // so resume cooperative checks immediately after its matching constructor returns.
            if (pendingConstructions > 0) {
                continue;
            }
            if (instruction instanceof JumpInsnNode
                    || instruction instanceof LookupSwitchInsnNode
                    || instruction instanceof TableSwitchInsnNode) {
                method.instructions.insertBefore(instruction, checkpoint());
            } else if (instruction instanceof MethodInsnNode
                    || instruction instanceof InvokeDynamicInsnNode) {
                method.instructions.insert(instruction, checkpoint());
            } else if (oneDimensionalArray(instruction)) {
                method.instructions.insertBefore(instruction, arrayCheck());
            } else if (instruction instanceof MultiANewArrayInsnNode multiArray) {
                method.instructions.insertBefore(
                        instruction, multiArrayCheck(method, multiArray.dims));
            }
        }
    }

    private static AbstractInsnNode firstExecutable(AbstractInsnNode[] instructions) {
        for (AbstractInsnNode instruction : instructions) {
            if (instruction.getOpcode() >= 0) {
                return instruction;
            }
        }
        return null;
    }

    private static AbstractInsnNode firstExecutableAfter(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static boolean oneDimensionalArray(AbstractInsnNode instruction) {
        return instruction instanceof IntInsnNode value && value.getOpcode() == Opcodes.NEWARRAY
                || instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.ANEWARRAY;
    }

    private static InsnList checkpoint() {
        InsnList check = new InsnList();
        check.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BUDGET, "checkpoint", "()V", false));
        return check;
    }

    private static InsnList arrayCheck() {
        InsnList check = new InsnList();
        check.add(new InsnNode(Opcodes.DUP));
        check.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BUDGET, "checkArrayLength", "(I)V", false));
        return check;
    }

    private static InsnList handlerGuard() {
        InsnList guard = new InsnList();
        guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BUDGET,
                "rethrowIfExceeded",
                "(Ljava/lang/Throwable;)V",
                false));
        return guard;
    }

    private static InsnList multiArrayCheck(MethodNode method, int dimensions) {
        if (dimensions < 1 || dimensions > 4) {
            throw new IllegalArgumentException("rule multi-array dimensions must be between 1 and 4");
        }
        int[] locals = new int[dimensions];
        InsnList check = new InsnList();
        for (int index = dimensions - 1; index >= 0; index--) {
            locals[index] = method.maxLocals++;
            check.add(new VarInsnNode(Opcodes.ISTORE, locals[index]));
        }
        addLoads(check, locals);
        String descriptor = Type.getMethodDescriptor(
                Type.VOID_TYPE,
                java.util.Collections.nCopies(dimensions, Type.INT_TYPE).toArray(Type[]::new));
        check.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BUDGET, "checkMultiArray", descriptor, false));
        addLoads(check, locals);
        return check;
    }

    private static void addLoads(InsnList instructions, int[] locals) {
        for (int local : locals) {
            instructions.add(new VarInsnNode(Opcodes.ILOAD, local));
        }
    }
}
