#include "backend/shutdowncoordinator.h"
#include "backend/instanceguard.h"
#include <QCoreApplication>
#include <SDL.h>
#include <atomic>
#include <cstdio>
#include <thread>
#include <chrono>
#include <csignal>
#include <unistd.h>

using namespace ProcessLifecycle;
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    const auto args = app.arguments();
    if (args.size() < 2) return 2;
    auto& shutdown = ShutdownCoordinator::instance();
    shutdown.setDeadlineDurationMs(400);
    SDL_SetHintWithPriority("SDL_NO_SIGNAL_HANDLERS", "1", SDL_HINT_OVERRIDE);
    if (!shutdown.installProcessSignalHandling()) return 3;
    if (SDL_Init(SDL_INIT_EVENTS | SDL_INIT_TIMER) != 0) return 4;
    // Reinitializing SDL must not replace our handlers.
    SDL_Quit();
    if (SDL_Init(SDL_INIT_EVENTS | SDL_INIT_TIMER) != 0) return 5;
    const QString mode = args[1];
    if (mode == "signal" || mode == "late") {
        if (mode == "late") shutdown.requestShutdown(ShutdownReason::ReplaceRequest);
        ActiveSessionRegistry::Snapshot snapshot;
        ActiveSessionRegistry::instance().registerSession(&app, [mode](ShutdownReason reason) {
            if (mode == "late" && reason != ShutdownReason::ReplaceRequest) _exit(6);
            _exit(0);
        }, snapshot);
    } else if (mode == "repeat") {
        shutdown.requestShutdown(ShutdownReason::ApplicationExit);
        std::thread([&shutdown] {
            for (;;) {
                std::this_thread::sleep_for(std::chrono::milliseconds(30));
                shutdown.requestShutdown(ShutdownReason::ApplicationExit);
            }
        }).detach();
    } else if (mode == "registry") {
        std::atomic<bool> entered{false}, release{false}, removed{false};
        ActiveSessionRegistry::instance().registerSession(&app, [&](ShutdownReason) {
            entered = true;
            while (!release) std::this_thread::yield();
        }, {});
        std::thread notify([] { ActiveSessionRegistry::instance().notify(ShutdownReason::ApplicationExit); });
        while (!entered) std::this_thread::yield();
        std::thread remove([&] { ActiveSessionRegistry::instance().unregisterSession(&app); removed = true; });
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
        if (removed) _exit(7); // must not destroy a target while callback runs
        release = true; notify.join(); remove.join();
        return removed ? 0 : 8;
    } else if (mode == "owner-transition") {
        auto& registry = ActiveSessionRegistry::instance();
        ActiveSessionRegistry::Snapshot old, next;
        old.hostUuid = "old"; old.appId = 1;
        next.hostUuid = "new"; next.appId = 2;
        bool newNotified = false;
        registry.registerSession(&app, [](ShutdownReason) {}, old);
        registry.registerSession(&shutdown, [&](ShutdownReason) { newNotified = true; }, next);
        registry.unregisterSession(&app, true);
        if (registry.snapshot().hostUuid != "new") return 15;
        registry.notify(ShutdownReason::ReplaceRequest);
        registry.unregisterSession(&shutdown);
        return newNotified && registry.snapshot().appId == 0 ? 0 : 16;
    } else if (mode == "retained") {
        ActiveSessionRegistry::Snapshot snapshot; snapshot.hostUuid = "recovery-host"; snapshot.appId = 42;
        auto& registry = ActiveSessionRegistry::instance();
        registry.registerSession(&app, [](ShutdownReason) {}, snapshot);
        registry.unregisterSession(&app, true);
        if (registry.snapshot().hostUuid != "recovery-host") return 11;
        registry.clearRecovery(&shutdown);
        if (registry.snapshot().appId != 42) return 12;
        registry.clearRecovery(&app);
        return registry.snapshot().appId == 0 ? 0 : 13;
    } else if (mode == "delayed-cleanup") {
        shutdown.setQuitDispatcher([] { _exit(14); });
        ActiveSessionRegistry::instance().registerSession(&app, [](ShutdownReason) {
            std::thread([] {
                std::this_thread::sleep_for(std::chrono::milliseconds(120));
                _exit(0); // completion, not the coordinator, owns application quit
            }).detach();
        }, {});
        shutdown.requestShutdown(ShutdownReason::ApplicationExit);
    } else if (mode == "instance" || mode == "hung-instance" || mode == "pending-instance" || mode == "recovery-instance") {
        if (args.size() != 5) return 9;
        auto& guard = InstanceGuard::instance();
        QString error;
        const auto result = guard.enter(args[2], args[3], args[4] == "gaming",
            ShutdownCoordinator::monotonicNowNs(), &error);
        if (result == InstanceGuard::Activated) { puts("ACTIVATED"); return 0; }
        if (result == InstanceGuard::Failed) { fprintf(stderr, "%s\n", qPrintable(error)); return 10; }
        ActiveSessionRegistry::Snapshot snapshot; snapshot.hostUuid = "paired-host"; snapshot.appId = 42;
        auto complete = [] {
            std::thread([] { std::this_thread::sleep_for(std::chrono::milliseconds(120)); _exit(0); }).detach();
        };
        if (mode != "pending-instance") {
            ActiveSessionRegistry::instance().registerSession(&app, [mode, complete](ShutdownReason) {
                if (mode != "hung-instance") complete();
            }, snapshot);
            if (mode == "recovery-instance") ActiveSessionRegistry::instance().unregisterSession(&app, true);
        }
        if (mode != "hung-instance") shutdown.setQuitDispatcher(complete);
        printf("PRIMARY %s %d\n", qPrintable(guard.handoff().hostUuid), guard.handoff().appId);
    }
    puts("READY"); fflush(stdout);
    // Deliberately never pump either Qt or SDL: this reproduces an unfocused
    // GUI, blocked main thread, and in-progress stream connection.
    for (;;) pause();
}
