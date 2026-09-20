package example.client.staticworld;

import com.github.mcmodderanchor.simplebedrockmodel.SimpleBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.Source;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.world.StaticWorldRenderer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import example.init.ExampleModRegister;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * 原型测试入口：{@code /sbmstatic on|off|stats|drop}，以及压测挂具
 * {@code /sbmstatic stress <same|models|all|dynamic> [count]}、{@code /sbmstatic stress clear}。
 * 开关是运行时的，压测实例是虚拟的（不依赖世界方块），可以在同一场景里做静态/动态 A/B。
 */
@Mod.EventBusSubscriber(modid = SimpleBedrockModel.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExampleStaticCommands {
    private ExampleStaticCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sbmstatic")
                .executes(context -> report(context))
                .then(Commands.literal("on").executes(context -> {
                    StaticWorldRenderer.setEnabled(true);
                    return report(context);
                }))
                .then(Commands.literal("off").executes(context -> {
                    StaticWorldRenderer.setEnabled(false);
                    return report(context);
                }))
                .then(Commands.literal("stats").executes(ExampleStaticCommands::report))
                .then(Commands.literal("drop").executes(context -> {
                    StaticWorldRenderer.invalidate(Source.Kind.RESOURCES);
                    return report(context);
                }))
                .then(Commands.literal("stress")
                        .then(Commands.literal("clear").executes(context -> {
                            ExampleStressSource.clear();
                            return report(context);
                        }))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(context -> stress(context, StringArgumentType.getString(context, "mode"), 48))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                                        .executes(context -> stress(context,
                                                StringArgumentType.getString(context, "mode"),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("relight")
                        .then(Commands.literal("on").executes(context -> {
                            ExampleStaticSource.setInPlaceLightUpdate(true);
                            return report(context);
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            ExampleStaticSource.setInPlaceLightUpdate(false);
                            return report(context);
                        }))));
    }

    private static int stress(CommandContext<CommandSourceStack> context, String modeName, int count) {
        ExampleStressSource.Mode mode;
        try {
            mode = ExampleStressSource.Mode.valueOf(modeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            context.getSource().sendFailure(Component.literal("unknown mode: " + modeName
                    + " (same|models|all|dynamic)"));
            return 0;
        }
        // 客户端命令源的实体是 LocalPlayer，CommandSourceStack.getPlayerOrException() 只认 ServerPlayer，
        // 在这里必然抛「需要一名玩家」，所以直接从客户端取。
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            context.getSource().sendFailure(Component.literal("no client player"));
            return 0;
        }
        int spawned = ExampleStressSource.spawn(mode, count, player);
        context.getSource().sendSuccess(() -> Component.literal(
                "stress " + mode + ": spawned " + spawned + "/" + count), false);
        return report(context);
    }

    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(StaticWorldRenderer.stats()
                + " " + ExampleStaticSource.stats()
                + " " + ExampleStressSource.stats()), false);
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        return report(context.getSource());
    }
}
