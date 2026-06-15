package com.inkamboo.dropsweeper;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Dropsweeper implements ModInitializer {
	public static final String MOD_ID = "dropsweeper";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("DropSweeper 初始化开始");

		// 触发 Dustbin.INSTANCE 类加载，初始化 SimpleContainer
		LOGGER.info("Dustbin 已就绪: {} 个垃圾箱，每个 {} 槽位",
				Dustbin.getInstance().binCount(), Dustbin.BIN_SIZE);

		// 加载配置（首次启动会自动生成 config/dropsweeper.json）
		var configPath = DropsweeperConfig.configPath();
		DropsweeperConfig.get();
		LOGGER.info("配置已加载: {}", configPath);

		// 注册命令
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			DropsweeperCommand.register(dispatcher);
			LOGGER.info("/dropsweeper 命令注册完成");
		});

		// 注册自动周期清扫（在每个 server tick 末）
		ServerTickEvents.END_SERVER_TICK.register(AutoSweeper.INSTANCE::onServerTick);

		// 注册服务器生命周期事件
		registerLifecycle();
	}

	/**
	 * 注册服务器生命周期事件。
	 * <ul>
	 *   <li>{@code SERVER_STARTED}：从世界存档加载 Dustbin（持久化）</li>
	 *   <li>{@code SERVER_STOPPING}：保存 Dustbin（确保不丢失）</li>
	 * </ul>
	 */
	private void registerLifecycle() {
		// 主世界的 ResourceKey（主世界在 minecraft:overworld）
		var overworldKey = net.minecraft.resources.ResourceKey.create(
				net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "overworld"));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ServerLevel overworld = server.getLevel(overworldKey);
			if (overworld == null) {
				LOGGER.warn("找不到主世界，Dustbin 不会从存档加载");
				return;
			}
			// computeIfAbsent：有存档就读，没存档就调用 Dustbin::new 建新的
			Dustbin loaded = overworld.getDataStorage().computeIfAbsent(Dustbin.TYPE);

			// 按配置 bin 数量补齐。resize 会按配置数量直接截断（target < current 时丢失非空 bin 物品），
			// 玩家应当提前用 /dropsweeper dustbin 把要保留的物品取出来，再改配置重启。
			int configuredCount = DropsweeperConfig.get().dustbinCount;
			Dustbin.ResizeResult resizeResult = loaded.resize(configuredCount);

			Dustbin.setInstance(loaded);
			LOGGER.info("Dustbin 从存档加载完成，共 {} 个垃圾箱（配置要求 {} 个，resize 结果：增加 {} / 删除 {} / 丢失非空 {}）",
					loaded.binCount(), configuredCount,
					resizeResult.added(), resizeResult.removed(), resizeResult.droppedNonEmpty());
			if (resizeResult.lostData()) {
				LOGGER.warn("Dustbin 启动时因配置 dustbinCount={} 减少了 {} 个非空 bin，物品数据已丢失（这些 bin 中原本存放的清扫物品）",
						configuredCount, resizeResult.droppedNonEmpty());
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// 关服时 MC 会自动写盘（因为 setDirty 被触发了），这里只打日志
			LOGGER.info("Dustbin 关服时已由 MC 自动保存");
		});
	}
}
