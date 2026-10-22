QT += core
QT -= gui
CONFIG += console c++11 link_pkgconfig
CONFIG -= app_bundle
PKGCONFIG += sdl2
TEMPLATE = app
TARGET = lifecycle_probe
INCLUDEPATH += ../../app
SOURCES += probe.cpp ../../app/backend/shutdowncoordinator.cpp ../../app/backend/instanceguard.cpp
HEADERS += ../../app/backend/shutdowncoordinator.h ../../app/backend/instanceguard.h
target.path = /app/libexec
INSTALLS += target

script.files = test_process_lifecycle.py
script.path = /app/libexec
INSTALLS += script
