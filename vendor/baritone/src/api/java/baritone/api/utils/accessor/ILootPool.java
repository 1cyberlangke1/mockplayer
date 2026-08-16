/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mockplayer.baritone.api.utils.accessor;

import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;

import java.util.List;

/** 读取 LootPool.entries（掉落物条目列表；MC 无公开 getter，走 mixin accessor）。 */
public interface ILootPool {

    List<LootPoolEntryContainer> entries();

}
