/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.plugin.update;

/**
 * Why one plugin could not be brought up to date.
 *
 * <p>Its message is written to be read by an operator and completes the sentence "could not update
 * {@code <plugin>}: ...", because that is the only place it is ever shown.</p>
 */
public sealed class PluginUpdateException extends Exception
    permits HangarSource.EmptyChannelException {

  private static final long serialVersionUID = 1L;

  PluginUpdateException(final String message) {
    super(message);
  }

  PluginUpdateException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
