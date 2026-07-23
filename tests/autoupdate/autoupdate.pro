QT += core network testlib
CONFIG += console c++11 testcase
CONFIG -= app_bundle

DEFINES += VERSION_STR=\\\"0.6.7\\\"
DEFINES += VIBERTEMIS_BUILD_COMMIT=\\\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\"
DEFINES += VIBERTEMIS_UPDATE_CHANNEL=\\\"rolling\\\"
DEFINES += VIBERTEMIS_BUILD_SEQUENCE=1234
DEFINES += VIBERTEMIS_APPLICATION_ID=\\\"com.artemisdesktop.ArtemisDesktopDev\\\"

TEMPLATE = app
TARGET = tst_autoupdate

INCLUDEPATH += $$PWD/../../app

SOURCES += \
    tst_autoupdate.cpp \
    $$PWD/../../app/backend/autoupdatechecker.cpp \
    $$PWD/../../app/backend/releaseversionselector.cpp \
    $$PWD/../../app/backend/buildinfo.cpp \
    $$PWD/../../app/backend/pendingupdate.cpp \
    $$PWD/../../app/backend/rollingupdateparser.cpp \
    $$PWD/../../app/backend/steamdecksession.cpp \
    $$PWD/../../app/backend/updatestatemachine.cpp

HEADERS += \
    $$PWD/../../app/backend/autoupdatechecker.h \
    $$PWD/../../app/backend/releaseversionselector.h \
    $$PWD/../../app/backend/buildinfo.h \
    $$PWD/../../app/backend/pendingupdate.h \
    $$PWD/../../app/backend/updateresult.h \
    $$PWD/../../app/backend/rollingupdateparser.h \
    $$PWD/../../app/backend/steamdecksession.h \
    $$PWD/../../app/backend/updatestatemachine.h

target.path = /app/libexec
INSTALLS += target
