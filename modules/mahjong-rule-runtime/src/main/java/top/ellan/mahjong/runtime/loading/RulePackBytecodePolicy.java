package top.ellan.mahjong.runtime.loading;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import top.ellan.mahjong.runtime.common.RulePackException;

/** Rejects reachable rule bytecode that can escape the deterministic provider boundary. */
final class RulePackBytecodePolicy {
    private RulePackBytecodePolicy() {}

    static void verify(ZipFile jar, String providerService) throws RulePackException {
        try {
            Map<String, ZipEntry> classes = new HashMap<>();
            jar.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                    .forEach(entry -> classes.put(
                            entry.getName().substring(0, entry.getName().length() - 6), entry));
            ZipEntry service = jar.getEntry(providerService);
            if (service == null) {
                return;
            }
            ArrayDeque<String> pending = new ArrayDeque<>();
            String providers = new String(
                    jar.getInputStream(service).readAllBytes(), StandardCharsets.UTF_8);
            providers.lines()
                    .map(line -> line.substring(0, commentStart(line)).trim())
                    .filter(line -> !line.isEmpty())
                    .map(name -> name.replace('.', '/'))
                    .filter(classes::containsKey)
                    .forEach(pending::addLast);
            Set<String> inspected = new HashSet<>();
            while (!pending.isEmpty()) {
                String className = pending.removeFirst();
                if (!inspected.add(className)) {
                    continue;
                }
                byte[] bytecode = jar.getInputStream(classes.get(className)).readAllBytes();
                Inspection inspection = inspectClass(bytecode);
                inspection.references().stream()
                        .filter(classes::containsKey)
                        .filter(reference -> !inspected.contains(reference))
                        .forEach(pending::addLast);
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new RulePackException("Unable to inspect rule-pack bytecode", failure);
        } catch (RulePackCapabilities.PolicyViolation violation) {
            throw new RulePackException(violation.getMessage());
        }
    }

    static Inspection inspectClass(byte[] bytecode) {
        Set<String> references = new HashSet<>();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        reference(superName, references);
                        RulePackCapabilities.verifyOwner(superName, "supertype", name);
                        if (interfaces != null) {
                            java.util.Collections.addAll(references, interfaces);
                            for (String implemented : interfaces) {
                                RulePackCapabilities.verifyOwner(implemented, "interface", name);
                            }
                        }
                    }

                    @Override
                    public FieldVisitor visitField(
                            int access, String name, String descriptor, String signature, Object value) {
                        descriptor(descriptor, references);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        if ((access & Opcodes.ACC_NATIVE) != 0) {
                            throw RulePackCapabilities.violation(
                                    "native method", reader.getClassName(), name);
                        }
                        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                            throw RulePackCapabilities.violation(
                                    "monitor synchronization", reader.getClassName(), name);
                        }
                        methodDescriptor(descriptor, references);
                        if (exceptions != null) {
                            java.util.Collections.addAll(references, exceptions);
                        }
                        return methodPolicy(reader.getClassName(), references);
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Inspection(reader.getClassName(), Set.copyOf(references));
    }

    private static MethodVisitor methodPolicy(String className, Set<String> references) {
        return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                    throw RulePackCapabilities.violation(
                            "monitor synchronization", className, "monitor");
                }
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                reference(type, references);
                if (opcode == Opcodes.NEW) {
                    RulePackCapabilities.verifyOwner(type, "constructor", className);
                }
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                reference(owner, references);
                descriptor(descriptor, references);
                RulePackCapabilities.verifyMember(owner, name, descriptor, true, className);
            }

            @Override
            public void visitMethodInsn(
                    int opcode, String owner, String name, String descriptor, boolean isInterface) {
                reference(owner, references);
                methodDescriptor(descriptor, references);
                RulePackCapabilities.verifyMember(owner, name, descriptor, false, className);
            }

            @Override
            public void visitInvokeDynamicInsn(
                    String name, String descriptor, Handle bootstrap, Object... arguments) {
                methodDescriptor(descriptor, references);
                RulePackCapabilities.verifyBootstrap(bootstrap, className);
                for (Object argument : arguments) {
                    constant(argument, references, className);
                }
            }

            @Override
            public void visitLdcInsn(Object value) {
                constant(value, references, className);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                descriptor(descriptor, references);
                if (dimensions > 4) {
                    throw RulePackCapabilities.violation(
                            "multi-array with more than four dimensions", className, "array");
                }
            }

            @Override
            public void visitTryCatchBlock(
                    org.objectweb.asm.Label start,
                    org.objectweb.asm.Label end,
                    org.objectweb.asm.Label handler,
                    String type) {
                reference(type, references);
            }
        };
    }

    private static void constant(Object value, Set<String> references, String source) {
        if (value instanceof Type type) {
            type(type, references);
        } else if (value instanceof Handle handle) {
            reference(handle.getOwner(), references);
            RulePackCapabilities.verifyMember(
                    handle.getOwner(), handle.getName(), handle.getDesc(), false, source);
        } else if (value instanceof ConstantDynamic dynamic) {
            descriptor(dynamic.getDescriptor(), references);
            RulePackCapabilities.verifyBootstrap(dynamic.getBootstrapMethod(), source);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                constant(dynamic.getBootstrapMethodArgument(index), references, source);
            }
        }
    }

    private static void methodDescriptor(String descriptor, Set<String> references) {
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            type(argument, references);
        }
        type(Type.getReturnType(descriptor), references);
    }

    private static void descriptor(String descriptor, Set<String> references) {
        type(Type.getType(descriptor), references);
    }

    private static void type(Type type, Set<String> references) {
        Type component = type;
        while (component.getSort() == Type.ARRAY) {
            component = component.getElementType();
        }
        if (component.getSort() == Type.OBJECT) {
            references.add(component.getInternalName());
        }
    }

    private static void reference(String value, Set<String> references) {
        if (value != null) {
            references.add(value);
        }
    }

    private static int commentStart(String line) {
        int comment = line.indexOf('#');
        return comment < 0 ? line.length() : comment;
    }

    record Inspection(String className, Set<String> references) {}
}
