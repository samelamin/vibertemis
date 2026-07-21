QT += core qml testlib
CONFIG += console c++11 testcase
CONFIG -= app_bundle

TEMPLATE = app
TARGET = tst_refreshrate

INCLUDEPATH += $$PWD/../../app

SOURCES += \
    tst_refreshrate.cpp \
    ../../app/settings/refreshrateparser.cpp

HEADERS += \
    ../../app/settings/refreshrateparser.h
