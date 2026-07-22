QT += core network testlib
CONFIG += console c++11 testcase
CONFIG -= app_bundle

DEFINES += VERSION_STR=\\\"0.6.7\\\"

TEMPLATE = app
TARGET = tst_autoupdate

INCLUDEPATH += $$PWD/../../app

SOURCES += \
    tst_autoupdate.cpp \
    $$PWD/../../app/backend/autoupdatechecker.cpp \
    $$PWD/../../app/backend/releaseversionselector.cpp

HEADERS += \
    $$PWD/../../app/backend/autoupdatechecker.h \
    $$PWD/../../app/backend/releaseversionselector.h

target.path = /app/libexec
INSTALLS += target
