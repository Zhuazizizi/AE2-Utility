package com.lhy.ae2utility.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.menu.me.common.MEStorageMenu;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;

/**
 * 与 AE2 终端 JEI 拉配方相同的本地预览：背包占位 + {@link MEStorageMenu#hasIngredient} + 可合成替补。
 *
 * <p>本类的判定结果只用来给 JEI 配方页上的编码按钮选底色（蓝/橙），因此这里按「可用量 vs 需求量」一次性
 * 汇总比较，而不是按材料数量逐份循环：旧的逐份实现在 1.89e7 / 2.15e9 这类配方数量下会执行上千万到上十亿次
 * 背包扫描与 {@code getByIngredient} 查询，直接把渲染线程卡死（issue #9）。</p>
 */
public final class TerminalJeRecipeTransferPreview {
    private static final byte MISSING = 1;
    private static final byte CRAFTABLE = 2;

    private static final long REFRESH_INTERVAL_TICKS = 10L;

    /**
     * 缺口不超过该值时仍然逐份核对（用于兼容合成终端里已预先放入合成格的原料），超过后不再按份数循环。
     * 超过该值时按「可合成替补」处理，与旧实现在这种情形下的结论一致。
     */
    private static final long MAX_UNITS_PER_SLOT = 64L;

    private static final Map<IRecipeSlotsView, Cached> CACHE = new WeakHashMap<>();

    private TerminalJeRecipeTransferPreview() {}

    private record Cached(byte[] flags, boolean anyResolved, long craftVersion, int invHash, long computedAtTick) {}

    public record PreviewSlots(
            List<IRecipeSlotView> missingSlots,
            List<IRecipeSlotView> craftableSlots,
            boolean anyResolved
    ) {
        public boolean anyCraftable() {
            return !craftableSlots.isEmpty();
        }
    }

    public static PreviewSlots compute(MEStorageMenu container, IRecipeSlotsView recipeSlots) {
        List<IRecipeSlotView> inputs = collectInputSlots(recipeSlots);

        long craftVersion = CraftableStateCache.cacheVersion();
        int invHash = playerInventoryHash(container);
        long tick = JeiClientCacheContext.getTickGeneration();

        Cached cached = CACHE.get(recipeSlots);
        if (cached != null
                && cached.flags.length == inputs.size()
                && cached.craftVersion == craftVersion
                && cached.invHash == invHash
                && tick - cached.computedAtTick < REFRESH_INTERVAL_TICKS) {
            return assemble(inputs, cached.flags, cached.anyResolved);
        }

        byte[] flags = new byte[inputs.size()];
        boolean anyResolved = computeFlags(container, inputs, flags);
        CACHE.put(recipeSlots, new Cached(flags, anyResolved, craftVersion, invHash, tick));
        return assemble(inputs, flags, anyResolved);
    }

    private static boolean computeFlags(MEStorageMenu container, List<IRecipeSlotView> inputs, byte[] flags) {
        var reservedTerminalAmounts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Object>();
        var playerItems = container.getPlayerInventory().items;
        var reservedPlayerItems = new int[playerItems.size()];
        boolean anyResolved = false;

        for (int idx = 0; idx < inputs.size(); idx++) {
            IRecipeSlotView slotView = inputs.get(idx);
            var ingredient = toIngredient(slotView);
            if (ingredient.isEmpty()) {
                continue;
            }

            long requiredCount = Math.max(getDisplayedStack(slotView).getCount(), 1);

            // 一次汇总「背包可用量 + 终端可用量」，复杂度只与背包格数和匹配条目数有关，与材料数量无关。
            long fromPlayer = countAvailableInPlayer(container, playerItems, ingredient, reservedPlayerItems);
            long fromTerminal = countAvailableInTerminal(container, ingredient, reservedTerminalAmounts);
            long available = fromPlayer + fromTerminal;

            if (available > 0L) {
                anyResolved = true;
            }

            long shortfall = requiredCount - available;
            if (shortfall <= 0L) {
                // 够用：只按「先背包后终端」的顺序记账，供后续槽位扣除，避免同一份材料被两个槽位重复计算。
                reserveInPlayer(container, playerItems, ingredient, reservedPlayerItems, requiredCount);
                reserveInTerminal(container, ingredient, reservedTerminalAmounts,
                        Math.max(0L, requiredCount - fromPlayer));
            } else {
                // 先把已有库存全部记账，余量再逐份确认。
                reserveInPlayer(container, playerItems, ingredient, reservedPlayerItems, fromPlayer);
                reserveInTerminal(container, ingredient, reservedTerminalAmounts, fromTerminal);

                if (shortfall <= MAX_UNITS_PER_SLOT) {
                    // 缺口小：沿用逐份核对，这样合成终端里已放进合成格的原料仍会被算进去
                    //（CraftingTermMenu#hasIngredient 会在仓库之前检查合成格）。
                    long filled = 0L;
                    for (long i = 0; i < shortfall; i++) {
                        if (!container.hasIngredient(ingredient, reservedTerminalAmounts)) {
                            break;
                        }
                        reservedTerminalAmounts.merge(ingredient, 1, Integer::sum);
                        filled++;
                        anyResolved = true;
                    }
                    shortfall -= filled;
                }
            }

            // 缺口仍然存在：判断是否有可合成替补。旧实现在每个未满足的份上都会落到这里，
            // 结论与剩余份数无关，所以聚合后一次判定即可。
            boolean craftable = false;
            boolean missing = false;
            if (shortfall > 0L) {
                craftable = hasCraftableAlternative(container, ingredient);
                missing = !craftable;
            }

            byte f = 0;
            if (missing) {
                f |= MISSING;
            }
            if (craftable) {
                f |= CRAFTABLE;
            }
            flags[idx] = f;
        }

        return anyResolved;
    }

    /** 统计玩家背包里当前还能用于该原料的数量（跳过锁定槽位与已被前面槽位占用的份数）。 */
    private static long countAvailableInPlayer(MEStorageMenu container, List<ItemStack> playerItems,
            Ingredient ingredient, int[] reservedPlayerItems) {
        long total = 0L;
        for (int slot = 0; slot < playerItems.size(); slot++) {
            if (container.isPlayerInventorySlotLocked(slot)) {
                continue;
            }

            var stack = playerItems.get(slot);
            if (stack.isEmpty() || !ingredient.test(stack)) {
                continue;
            }

            int free = stack.getCount() - reservedPlayerItems[slot];
            if (free > 0) {
                total += free;
            }
        }
        return total;
    }

    /** 统计终端仓库里当前还能用于该原料的数量（同一份库存不会被两个槽位重复计入）。 */
    private static long countAvailableInTerminal(MEStorageMenu container, Ingredient ingredient,
            it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Object> reservedTerminalAmounts) {
        var clientRepo = container.getClientRepo();
        if (clientRepo == null) {
            return 0L;
        }

        long total = 0L;
        for (var entry : clientRepo.getByIngredient(ingredient)) {
            long free = entry.getStoredAmount() - reservedTerminalAmounts.getOrDefault(entry, 0);
            if (free > 0L) {
                total += free;
            }
        }
        return total;
    }

    private static void reserveInPlayer(MEStorageMenu container, List<ItemStack> playerItems,
            Ingredient ingredient, int[] reservedPlayerItems, long amount) {
        long remaining = amount;
        for (int slot = 0; slot < playerItems.size() && remaining > 0L; slot++) {
            if (container.isPlayerInventorySlotLocked(slot)) {
                continue;
            }

            var stack = playerItems.get(slot);
            if (stack.isEmpty() || !ingredient.test(stack)) {
                continue;
            }

            int free = stack.getCount() - reservedPlayerItems[slot];
            if (free <= 0) {
                continue;
            }

            int take = (int) Math.min(free, remaining);
            reservedPlayerItems[slot] += take;
            remaining -= take;
        }
    }

    private static void reserveInTerminal(MEStorageMenu container, Ingredient ingredient,
            it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Object> reservedTerminalAmounts, long amount) {
        if (amount <= 0L) {
            return;
        }

        var clientRepo = container.getClientRepo();
        if (clientRepo == null) {
            return;
        }

        long remaining = amount;
        for (var entry : clientRepo.getByIngredient(ingredient)) {
            if (remaining <= 0L) {
                break;
            }

            long free = entry.getStoredAmount() - reservedTerminalAmounts.getOrDefault(entry, 0);
            if (free <= 0L) {
                continue;
            }

            long take = Math.min(free, Math.min(remaining, Integer.MAX_VALUE));
            reservedTerminalAmounts.merge(entry, (int) take, Integer::sum);
            remaining -= take;
        }
    }

    private static PreviewSlots assemble(List<IRecipeSlotView> inputs, byte[] flags, boolean anyResolved) {
        List<IRecipeSlotView> missingSlots = new ArrayList<>();
        List<IRecipeSlotView> craftableSlots = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            byte f = flags[i];
            if ((f & MISSING) != 0) {
                missingSlots.add(inputs.get(i));
            }
            if ((f & CRAFTABLE) != 0) {
                craftableSlots.add(inputs.get(i));
            }
        }
        return new PreviewSlots(missingSlots, craftableSlots, anyResolved);
    }

    private static int playerInventoryHash(MEStorageMenu container) {
        var items = container.getPlayerInventory().items;
        int h = 1;
        for (int slot = 0; slot < items.size(); slot++) {
            var stack = items.get(slot);
            int stackHash = stack.isEmpty() ? 0 : stack.hasTag() ? stack.getTag().hashCode() : 0;
            h = 31 * h + stackHash;
            h = 31 * h + stack.getCount();
            h = 31 * h + (container.isPlayerInventorySlotLocked(slot) ? 1 : 0);
        }
        return h;
    }

    private static List<IRecipeSlotView> collectInputSlots(IRecipeSlotsView recipeSlots) {
        return recipeSlots.getSlotViews(RecipeIngredientRole.INPUT).stream()
                .filter(TerminalJeRecipeTransferPreview::hasItemStack)
                .toList();
    }

    private static boolean hasCraftableAlternative(MEStorageMenu container, Ingredient ingredient) {
        return ClientRepoCraftableIndex.hasCraftableItemMatchingIngredient(container, ingredient);
    }

    private static boolean hasItemStack(IRecipeSlotView slotView) {
        return slotView.getDisplayedItemStack().isPresent() || slotView.getItemStacks().findAny().isPresent();
    }

    private static Ingredient toIngredient(IRecipeSlotView slotView) {
        return Ingredient.of(slotView.getItemStacks().map(ItemStack::copy));
    }

    private static ItemStack getDisplayedStack(IRecipeSlotView slotView) {
        return slotView.getDisplayedItemStack()
                .or(() -> slotView.getItemStacks().findFirst())
                .orElse(ItemStack.EMPTY);
    }
}
