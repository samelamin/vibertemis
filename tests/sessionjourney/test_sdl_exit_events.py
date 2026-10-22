#!/usr/bin/env python3
"""Build the production SDL exit classifier and exercise signal/window semantics."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HARNESS = r'''
#include "streaming/sessionevents.h"
#include <cassert>
#include <csignal>
#include <iostream>
int main() {
    const int initResult = SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS);
    assert(initResult == 0);
    const bool distinct = SessionEvents::configureWindowCloseHandling();
    assert(distinct); // This host-side CI test requires SDL >= 2.0.22.
    assert(!SDL_GetHintBoolean("SDL_QUIT_ON_LAST_WINDOW_CLOSE", SDL_TRUE));
    auto* window = SDL_CreateWindow("Exit test", 0, 0, 32, 32, SDL_WINDOW_HIDDEN);
    assert(window);
    const Uint32 id = SDL_GetWindowID(window);
    SDL_Event event{};
    event.type = SDL_WINDOWEVENT;
    event.window.event = SDL_WINDOWEVENT_CLOSE;
    event.window.windowID = id;
    using SessionEvents::ExitAction;
    assert(SessionEvents::classifyExit(event, id, distinct) == ExitAction::Disconnect);
    assert(SessionEvents::classifyExit(event, id + 1, distinct) == ExitAction::None);
    assert(SessionEvents::classifyExit(event, id, false) == ExitAction::None);
    assert(SessionEvents::classifyExit(event, id, distinct, true) == ExitAction::ExitApplication);
    assert(SessionEvents::classifyExit(event, id + 1, distinct, true) == ExitAction::None);
    event.type = SDL_QUIT;
    assert(SessionEvents::classifyExit(event, id, distinct) == ExitAction::ExitApplication);
    assert(SessionEvents::classifyExit(event, id, false) == ExitAction::None);
    event.type = SDL_USEREVENT;
    assert(SessionEvents::classifyExit(event, id, distinct) == ExitAction::None);
    SDL_DestroyWindow(window);
    SDL_Quit();
    std::cout << "PASS: Desktop window close disconnects; Gaming close and SDL_QUIT exit; stale/other events ignored\n";
}
'''


def main():
    flags = shlex.split(subprocess.check_output(
        ['pkg-config', '--cflags', '--libs', 'sdl2'], text=True))
    with tempfile.TemporaryDirectory(prefix='vibertemis-sdl-exit-') as tmp:
        directory = Path(tmp)
        source = directory / 'test.cpp'
        source.write_text(HARNESS)
        binary = directory / 'test'
        subprocess.run(['c++', '-std=c++11', '-UNDEBUG', '-I' + str(ROOT / 'app'),
                        str(source), str(ROOT / 'app/streaming/sessionevents.cpp'),
                        '-o', str(binary), *flags], check=True)
        subprocess.run([str(binary)], check=True,
                       env=dict(os.environ, SDL_VIDEODRIVER='dummy'), timeout=10)


if __name__ == '__main__':
    main()
