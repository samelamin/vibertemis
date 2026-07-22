QT += core testlib
CONFIG += console c++11 testcase
CONFIG -= app_bundle

TEMPLATE = app
TARGET = tst_autoupdate

INCLUDEPATH += $$PWD/../../app

SOURCES += \
    tst_autoupdate.cpp \
    $$PWD/../../app/backend/releaseversionselector.cpp

HEADERS += \
    $$PWD/../../app/backend/releaseversionselector.h

target.path = /app/libexec
INSTALLS += target
