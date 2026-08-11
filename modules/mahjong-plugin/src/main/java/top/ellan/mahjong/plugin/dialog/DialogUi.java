package top.ellan.mahjong.plugin.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/** Small, consistent visual vocabulary for MahjongPaper native dialogs. */
final class DialogUi {
    private static final int BODY_WIDTH = 420;
    private static final int BUTTON_WIDTH = 150;
    private static final ClickCallback.Options CALLBACK_OPTIONS =
            ClickCallback.Options.builder()
                    .uses(1)
                    .lifetime(Duration.ofMinutes(10))
                    .build();

    private DialogUi() {}

    static Dialog multi(
            Component title,
            Component body,
            List<? extends DialogInput> inputs,
            List<ActionButton> actions,
            int columns,
            ActionButton exit) {
        DialogBase base = base(title, body, inputs);
        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(actions)
                        .columns(columns)
                        .exitAction(exit)
                        .build()));
    }

    static Dialog confirmation(
            Component title,
            Component body,
            ActionButton confirm,
            ActionButton cancel) {
        return Dialog.create(factory -> factory.empty()
                .base(base(title, body, List.of()))
                .type(DialogType.confirmation(confirm, cancel)));
    }

    static ActionButton button(
            Component label, Component tooltip, DialogActionCallback callback) {
        return ActionButton.builder(label)
                .tooltip(tooltip)
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(callback, CALLBACK_OPTIONS))
                .build();
    }

    static ActionButton button(Component label, DialogActionCallback callback) {
        return button(label, Component.empty(), callback);
    }

    private static DialogBase base(
            Component title, Component body, List<? extends DialogInput> inputs) {
        return DialogBase.builder(title)
                .externalTitle(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(body, BODY_WIDTH)))
                .inputs(inputs)
                .build();
    }
}
