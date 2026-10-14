#pragma once
#include <SDL.h>

namespace SessionEvents {
enum class ExitAction { None, Disconnect, ExitApplication };

// Older SDL runtimes cannot separate closing the last window from quitting.
bool configureWindowCloseHandling();
ExitAction classifyExit(const SDL_Event& event, Uint32 streamWindowId,
                        bool distinguishApplicationQuit);
}
