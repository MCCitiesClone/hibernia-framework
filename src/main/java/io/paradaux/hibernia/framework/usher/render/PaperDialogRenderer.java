package io.paradaux.hibernia.framework.usher.render;

import com.google.inject.Singleton;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.paradaux.hibernia.framework.usher.ButtonSpec;
import io.paradaux.hibernia.framework.usher.DialogView;
import io.paradaux.hibernia.framework.usher.Text;
import io.paradaux.hibernia.framework.usher.input.DialogInputSpec;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The production {@link DialogRenderer}: converts a {@link DialogView} spec into a Paper
 * {@link Dialog} and shows it. This is the only class in the dialog tier that depends on Paper's dialog
 * runtime — it is intentionally thin glue and is exercised in-game rather than in unit tests.
 */
@Singleton
public final class PaperDialogRenderer implements DialogRenderer {

    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder().build();

    @Override
    public void show(Audience viewer, DialogView view,
                     Function<Text, Component> text,
                     Function<ButtonSpec, DialogActionCallback> callbacks) {
        DialogBase base = base(view, text);
        DialogType type = type(view, text, callbacks);
        Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(type));
        viewer.showDialog(dialog);
    }

    @Override
    public void close(Audience viewer) {
        viewer.closeDialog();
    }

    private DialogBase base(DialogView view, Function<Text, Component> text) {
        DialogBase.Builder builder = DialogBase.builder(text.apply(view.title()))
                .canCloseWithEscape(view.canCloseWithEscape())
                // Never pause the game: a server dialog can't pause it anyway, and Paper rejects a
                // pausing dialog whose after_action doesn't unpause (e.g. the NONE default), which would
                // otherwise make every multiAction/notice screen throw at render time.
                .pause(false)
                .afterAction(afterAction(view.afterAction()));
        if (view.externalTitle() != null) {
            builder.externalTitle(text.apply(view.externalTitle()));
        }
        if (!view.bodies().isEmpty()) {
            builder.body(bodies(view, text));
        }
        if (!view.inputs().isEmpty()) {
            builder.inputs(inputs(view, text));
        }
        return builder.build();
    }

    private List<DialogBody> bodies(DialogView view, Function<Text, Component> text) {
        List<DialogBody> out = new ArrayList<>(view.bodies().size());
        for (DialogView.Body body : view.bodies()) {
            if (body instanceof DialogView.Body.Message message) {
                out.add(DialogBody.plainMessage(text.apply(message.text())));
            } else if (body instanceof DialogView.Body.Item item) {
                out.add(DialogBody.item(item.item()).build());
            }
        }
        return out;
    }

    private List<DialogInput> inputs(DialogView view, Function<Text, Component> text) {
        List<DialogInput> out = new ArrayList<>(view.inputs().size());
        for (DialogInputSpec spec : view.inputs()) {
            out.add(input(spec, text));
        }
        return out;
    }

    private DialogInput input(DialogInputSpec spec, Function<Text, Component> text) {
        Component label = text.apply(spec.label());
        return switch (spec.kind()) {
            case TEXT -> {
                var b = DialogInput.text(spec.key(), label);
                if (spec.width() > 0) b.width(spec.width());
                yield b.build();
            }
            case BOOLEAN -> DialogInput.bool(spec.key(), label)
                    .initial(spec.initial() != null && spec.initial() != 0f)
                    .build();
            case TOGGLE, OPTION -> {
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>(spec.options().size());
                for (DialogInputSpec.OptionSpec option : spec.options()) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(
                            option.id(), text.apply(option.label()), option.initial()));
                }
                var b = DialogInput.singleOption(spec.key(), label, entries);
                if (spec.width() > 0) b.width(spec.width());
                yield b.build();
            }
            case NUMBER -> {
                var b = DialogInput.numberRange(spec.key(), label, spec.min(), spec.max());
                if (spec.initial() != null) b.initial(spec.initial());
                if (spec.step() != null) b.step(spec.step());
                if (spec.width() > 0) b.width(spec.width());
                yield b.build();
            }
        };
    }

    private DialogType type(DialogView view, Function<Text, Component> text,
                            Function<ButtonSpec, DialogActionCallback> callbacks) {
        return switch (view.kind()) {
            case NOTICE -> view.buttons().isEmpty()
                    ? DialogType.notice()
                    : DialogType.notice(button(view.buttons().get(0), text, callbacks));
            case CONFIRMATION -> DialogType.confirmation(
                    button(view.buttons().get(0), text, callbacks),
                    button(view.buttons().get(1), text, callbacks));
            case MULTI_ACTION -> {
                List<ActionButton> actions = new ArrayList<>(view.buttons().size());
                for (ButtonSpec spec : view.buttons()) {
                    actions.add(button(spec, text, callbacks));
                }
                MultiActionType.Builder b = DialogType.multiAction(actions).columns(view.columns());
                if (view.exitButton() != null) {
                    b.exitAction(button(view.exitButton(), text, callbacks));
                }
                yield b.build();
            }
        };
    }

    private ActionButton button(ButtonSpec spec, Function<Text, Component> text,
                                Function<ButtonSpec, DialogActionCallback> callbacks) {
        ActionButton.Builder builder = ActionButton.builder(text.apply(spec.label()))
                .action(DialogAction.customClick(callbacks.apply(spec), CALLBACK_OPTIONS));
        if (spec.tooltip() != null) {
            builder.tooltip(text.apply(spec.tooltip()));
        }
        if (spec.width() > 0) {
            builder.width(spec.width());
        }
        return builder.build();
    }

    private static DialogBase.DialogAfterAction afterAction(DialogView.AfterAction afterAction) {
        return switch (afterAction) {
            case NONE -> DialogBase.DialogAfterAction.NONE;
            case CLOSE -> DialogBase.DialogAfterAction.CLOSE;
            case WAIT_FOR_RESPONSE -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE;
        };
    }
}
