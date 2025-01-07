/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [PresentationState] created based on the passed [Player] and launches a
 * coroutine to listen to [Player]'s changes. If the [Player] instance changes between compositions,
 * produces and remembers a new value.
 */
@UnstableApi
@Composable
fun rememberPresentationState(player: Player): PresentationState {
  val presentationState = remember(player) { PresentationState(player) }
  LaunchedEffect(player) { presentationState.observe() }
  return presentationState
}

/**
 * State that holds information to correctly deal with UI components related to the rendering of
 * frames to a surface.
 *
 * @property[showSurface] set to true when the Player emits [Player.EVENT_RENDERED_FIRST_FRAME] and
 *   reset to false on [Player.EVENT_TRACKS_CHANGED] depending on the number and type of tracks.
 * @property[keepContentOnReset] whether the currently displayed video frame or media artwork is
 *   kept visible when tracks change. Defaults to false.
 */
@UnstableApi
class PresentationState(private val player: Player) {
  var showSurface by mutableStateOf(false)
    private set

  var keepContentOnReset: Boolean = false
    set(value) {
      field = value
      maybeHideSurface(player)
    }

  private var lastPeriodUidWithTracks: Any? = null

  suspend fun observe(): Nothing =
    player.listen { events ->
      if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
        showSurface = true
      }
      if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
        maybeHideSurface(player)
      }
    }

  private fun maybeHideSurface(player: Player) {
    val hasTracks =
      player.isCommandAvailable(Player.COMMAND_GET_TRACKS) && !player.currentTracks.isEmpty
    if (!shouldKeepSurfaceVisible(player)) {
      if (!keepContentOnReset && !hasTracks) {
        showSurface = false
      }
      if (hasTracks && !hasSelectedVideoTrack()) {
        showSurface = false
      }
    }
  }

  private fun shouldKeepSurfaceVisible(player: Player): Boolean {
    // Suppress the shutter if transitioning to an unprepared period within the same window. This
    // is necessary to avoid closing the shutter (i.e hiding the surface) when such a transition
    // occurs. See: https://github.com/google/ExoPlayer/issues/5507.
    val timeline =
      if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) player.currentTimeline
      else Timeline.EMPTY

    if (timeline.isEmpty) {
      lastPeriodUidWithTracks = null
      return false
    }

    val period = Timeline.Period()
    if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS) && !player.currentTracks.isEmpty) {
      lastPeriodUidWithTracks =
        timeline.getPeriod(player.currentPeriodIndex, period, /* setIds= */ true).uid
    } else
      lastPeriodUidWithTracks?.let {
        val lastPeriodIndexWithTracks = timeline.getIndexOfPeriod(it)
        if (lastPeriodIndexWithTracks != C.INDEX_UNSET) {
          val lastWindowIndexWithTracks =
            timeline.getPeriod(lastPeriodIndexWithTracks, period).windowIndex
          if (player.currentMediaItemIndex == lastWindowIndexWithTracks) {
            // We're in the same media item, keep the surface visible, don't show the shutter.
            return true
          }
        }
        lastPeriodUidWithTracks = null
      }
    return false
  }

  private fun hasSelectedVideoTrack(): Boolean {
    return player.isCommandAvailable(Player.COMMAND_GET_TRACKS) &&
      player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
  }
}