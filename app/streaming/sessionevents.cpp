#include "sessionevents.h"

namespace SessionEvents {
bool configureWindowCloseHandling()
{
    SDL_version version;
    SDL_GetVersion(&version);
    return SDL_VERSIONNUM(version.major, version.minor, version.patch) >=
               SDL_VERSIONNUM(2, 0, 22) &&
           SDL_SetHintWithPriority("SDL_QUIT_ON_LAST_WINDOW_CLOSE", "0", SDL_HINT_OVERRIDE);
}

ExitAction classifyExit(const SDL_Event& event, Uint32 streamWindowId,
                        bool distinguishApplicationQuit)
{
    if (!distinguishApplicationQuit) return ExitAction::None;
    if (event.type == SDL_QUIT) return ExitAction::ExitApplication;
    if (event.type == SDL_WINDOWEVENT && event.window.event == SDL_WINDOWEVENT_CLOSE &&
        event.window.windowID == streamWindowId) return ExitAction::Disconnect;
    return ExitAction::None;
}
}
