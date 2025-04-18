package snownee.jade.util;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.google.common.base.Strings;
import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.block.BlockPickInteractionAware;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.mininglevel.v1.FabricMineableTags;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import snownee.jade.Jade;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.Identifiers;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.ItemView;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.command.JadeServerCommand;
import snownee.jade.impl.BlockAccessorImpl;
import snownee.jade.impl.EntityAccessorImpl;
import snownee.jade.impl.WailaClientRegistration;
import snownee.jade.impl.WailaCommonRegistration;
import snownee.jade.impl.config.PluginConfig;
import snownee.jade.mixin.AbstractHorseAccess;

public final class CommonProxy implements ModInitializer {

	public static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("c:bosses"));
	private static final Direction[] DIRECTIONS = Direction.values();

	@Nullable
	public static String getLastKnownUsername(UUID uuid) {
		return UsernameCache.getLastKnownUsername(uuid);
	}

	public static File getConfigDirectory() {
		return FabricLoader.getInstance().getConfigDir().toFile();
	}

	public static boolean isShears(ItemStack tool) {
		return tool.getItem() instanceof ShearsItem;
	}

	public static boolean isShearable(BlockState state) {
		return state.is(FabricMineableTags.SHEARS_MINEABLE);
	}

	public static boolean isCorrectToolForDrops(BlockState state, Player player) {
		return player.hasCorrectToolForDrops(state);
	}

	public static String getModIdFromItem(ItemStack stack) {
		if (stack.getTag() != null && stack.getTag().contains("id")) {
			String s = stack.getTag().getString("id");
			if (s.contains(":")) {
				ResourceLocation id = ResourceLocation.tryParse(s);
				if (id != null) {
					return id.getNamespace();
				}
			}
		}
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
	}

	public static boolean isPhysicallyClient() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
	}

	@SuppressWarnings("UnstableApiUsage")
	public static List<ViewGroup<ItemStack>> wrapItemStorage(Object target, ServerPlayer player) {
		int size = 54;
		if (target instanceof AbstractHorseAccess horse) {
			return List.of(ItemView.fromContainer(horse.getInventory(), size, 2));
		}
		if (target instanceof Container container) {
			if (target instanceof ChestBlockEntity be) {
				if (be.getBlockState().getBlock() instanceof ChestBlock chestBlock) {
					Container compound = ChestBlock.getContainer(chestBlock, be.getBlockState(), be.getLevel(), be.getBlockPos(), false);
					if (compound != null) {
						container = compound;
					}
				}
			}
			return List.of(ItemView.fromContainer(container, size, 0));
		}
		if (player != null && target instanceof EnderChestBlockEntity) {
			return List.of(ItemView.fromContainer(player.getEnderChestInventory(), size, 0));
		}
		if (target instanceof SidedStorageBlockEntity be) {
			var storage = be.getItemStorage(null);
			if (storage != null) {
				return List.of(JadeFabricUtils.fromItemStorage(storage, size, 0));
			}
		}
		var storage = lookupBlock(ItemStorage.SIDED, target);
		if (storage != null) {
			return List.of(JadeFabricUtils.fromItemStorage(storage, size, 0));
		}
		return null;
	}

	@SuppressWarnings("UnstableApiUsage")
	public static List<ViewGroup<CompoundTag>> wrapFluidStorage(Object target, ServerPlayer player) {
		if (target instanceof SidedStorageBlockEntity be) {
			var storage = be.getFluidStorage(null);
			if (storage != null) {
				return JadeFabricUtils.fromFluidStorage(storage);
			}
		}
		var storage = lookupBlock(FluidStorage.SIDED, target);
		if (storage != null) {
			return JadeFabricUtils.fromFluidStorage(storage);
		}
		return null;
	}

	public static <T> T lookupBlock(BlockApiLookup<T, Direction> sided, Object target) {
		T found = null;
		if (target instanceof BlockEntity be) {
			for (Direction direction : DIRECTIONS) {
				T t = sided.find(be.getLevel(), be.getBlockPos(), be.getBlockState(), be, direction);
				if (found != t && found != null && t != null) {
					return null;
				}
				if (t != null) {
					found = t;
				}
			}
		}
		return found;
	}

	public static List<ViewGroup<CompoundTag>> wrapEnergyStorage(Object target, ServerPlayer player) {
		return null;
	}

	public static boolean isDevEnv() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	public static float getEnchantPowerBonus(BlockState state, Level world, BlockPos pos) {
		if (WailaClientRegistration.INSTANCE.customEnchantPowers.containsKey(state.getBlock())) {
			return WailaClientRegistration.INSTANCE.customEnchantPowers.get(state.getBlock()).getEnchantPowerBonus(state, world, pos);
		}
		return state.is(Blocks.BOOKSHELF) ? 1 : 0;
	}

	public static ResourceLocation getId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block);
	}

	public static ResourceLocation getId(EntityType<?> entityType) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
	}

	public static ResourceLocation getId(BlockEntityType<?> blockEntityType) {
		return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
	}

	public static ResourceLocation getId(PaintingVariant motive) {
		return BuiltInRegistries.PAINTING_VARIANT.getKey(motive);
	}

	public static String getPlatformIdentifier() {
		return "fabric";
	}

	public static MutableComponent getProfressionName(VillagerProfession profession) {
		return Component.translatable(EntityType.VILLAGER.getDescriptionId() + "." + BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath());
	}

	private static void registerServerCommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		JadeServerCommand.register(dispatcher);
	}

	public static boolean isBoss(Entity entity) {
		EntityType<?> entityType = entity.getType();
		return entityType.is(BOSSES) || entityType == EntityType.ENDER_DRAGON || entityType == EntityType.WITHER;
	}

	public static ItemStack getBlockPickedResult(BlockState state, Player player, BlockHitResult hitResult) {
		Block block = state.getBlock();
		if (block instanceof BlockPickInteractionAware) {
			return ((BlockPickInteractionAware) block).getPickedStack(state, player.level(), hitResult.getBlockPos(), player, hitResult);
		}
		return block.getCloneItemStack(player.level(), hitResult.getBlockPos(), state);
	}

	private static void playerJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
		ServerPlayer player = handler.player;
		Jade.LOGGER.info("Syncing config to {} ({})", player.getGameProfile().getName(), player.getGameProfile().getId());
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeUtf(Strings.nullToEmpty(PluginConfig.INSTANCE.getServerConfigs()));
		ServerPlayNetworking.send(player, Identifiers.PACKET_SERVER_PING, buf);

		if (server.isDedicatedServer())
			UsernameCache.setUsername(player.getUUID(), player.getGameProfile().getName());
	}

	public static boolean isModLoaded(String modid) {
		return FabricLoader.getInstance().isModLoaded(modid);
	}

	public static void loadComplete() {
		FabricLoader.getInstance().getEntrypointContainers(Jade.MODID, IWailaPlugin.class).forEach(entrypoint -> {
			ModMetadata metadata = entrypoint.getProvider().getMetadata();
			Jade.LOGGER.info("Start loading plugin from {}", metadata.getName());
			String className = null;
			try {
				IWailaPlugin plugin = entrypoint.getEntrypoint();
				WailaPlugin a = plugin.getClass().getDeclaredAnnotation(WailaPlugin.class);
				if (a != null && !Strings.isNullOrEmpty(a.value()) && !isModLoaded(a.value()))
					return;
				className = plugin.getClass().getName();
				plugin.register(WailaCommonRegistration.INSTANCE);
				if (isPhysicallyClient()) {
					plugin.registerClient(WailaClientRegistration.INSTANCE);
				}
			} catch (Throwable e) {
				Jade.LOGGER.error("Error loading plugin at {}", className, e);
			}
		});
		Jade.loadComplete();
	}

	@SuppressWarnings("UnstableApiUsage")
	public static Component getFluidName(JadeFluidObject fluidObject) {
		Fluid fluid = fluidObject.getType();
		CompoundTag nbt = fluidObject.getTag();
		return FluidVariantAttributes.getName(FluidVariant.of(fluid, nbt));
	}

	public static int showOrHideFromServer(Collection<ServerPlayer> players, boolean show) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeBoolean(show);
		for (ServerPlayer player : players) {
			ServerPlayNetworking.send(player, Identifiers.PACKET_SHOW_OVERLAY, buf);
		}
		return players.size();
	}

	@Override
	public void onInitialize() {
		ServerPlayNetworking.registerGlobalReceiver(Identifiers.PACKET_REQUEST_ENTITY, (server, player, handler, buf, responseSender) -> {
			EntityAccessorImpl.handleRequest(buf, player, server::execute, tag -> {
				FriendlyByteBuf data = PacketByteBufs.create();
				data.writeNbt(tag);
				responseSender.sendPacket(Identifiers.PACKET_RECEIVE_DATA, data);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(Identifiers.PACKET_REQUEST_TILE, (server, player, handler, buf, responseSender) -> {
			BlockAccessorImpl.handleRequest(buf, player, server::execute, tag -> {
				FriendlyByteBuf data = PacketByteBufs.create();
				data.writeNbt(tag);
				responseSender.sendPacket(Identifiers.PACKET_RECEIVE_DATA, data);
			});
		});

		CommandRegistrationCallback.EVENT.register(CommonProxy::registerServerCommand);
		ServerPlayConnectionEvents.JOIN.register(CommonProxy::playerJoin);
		UsernameCache.load();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (server.isDedicatedServer()) {
				loadComplete();
			}
		});
	}
}
