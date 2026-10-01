#define MyAppName "VibertemisVR Host Manager"
#define MyAppVersion "0.1.0.11"
#define MyAppPublisher "Vibertemis"

[Setup]
AppId={{8B9C7E2D-7A4B-4D8B-9F5A-2C6E1B3A4D8E}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\VibertemisVR
DefaultGroupName=VibertemisVR
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputBaseFilename=VibertemisVR-HostManager-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
UninstallDisplayIcon={app}\manager\VibertemisManager.App.exe
UninstallDisplayName={#MyAppName}
AppCopyright=Copyright (c) Vibertemis
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
SignedUninstaller=no
AppMutex=Local\VibertemisVRHostManager
CloseApplications=no
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Dirs]
Name: "{app}\manager"
Name: "{app}\manager\bin"

[Files]
; Manager app (single-file self-contained).
Source: "{#MyStagingRoot}\manager\VibertemisManager.App.exe"; DestDir: "{app}\manager"; Flags: ignoreversion
Source: "{#MyStagingRoot}\manager\VibertemisNetworkHelper.exe"; DestDir: "{app}\manager"; Flags: ignoreversion
Source: "{#MyStagingRoot}\manager\bin\vibertemis-host-companion.exe"; DestDir: "{app}\manager\bin"; Flags: ignoreversion
Source: "{#MyStagingRoot}\manager\prerequisites\*"; DestDir: "{app}\manager\prerequisites"; Flags: ignoreversion recursesubdirs
; Immutable native payload, staged unchanged.
Source: "{#MyStagingRoot}\runtime\ALVR Dashboard.exe"; DestDir: "{app}\runtime"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\driver.vrdrivermanifest"; DestDir: "{app}\runtime"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\bin\win64\driver_alvr_server.dll"; DestDir: "{app}\runtime\bin\win64"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\bin\win64\openvr_api.dll"; DestDir: "{app}\runtime\bin\win64"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\bin\win64\pyrowave-shared.dll"; DestDir: "{app}\runtime\bin\win64"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\bin\win64\alvr_server_openvr.pdb"; DestDir: "{app}\runtime\bin\win64"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\SOURCE.txt"; DestDir: "{app}\runtime"; Flags: ignoreversion
Source: "{#MyStagingRoot}\runtime\licenses\*"; DestDir: "{app}\runtime\licenses"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{group}\VibertemisVR Host Manager"; Filename: "{app}\manager\VibertemisManager.App.exe"; Tasks: startmenu

[Tasks]
Name: "startmenu"; Description: "Create a Start menu shortcut"; GroupDescription: "Additional icons"; Flags: checkedonce

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "VibertemisVRHostManager"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\manager\VibertemisManager.App.exe"; Description: "Open VibertemisVR Host Manager"; Flags: nowait postinstall skipifsilent

[Code]
var
  WmiServices: Variant;

procedure InitProcessScanner();
var
  Locator: Variant;
begin
  if VarIsEmpty(WmiServices) then
  begin
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    WmiServices := Locator.ConnectServer('.', 'root\cimv2');
  end;
end;

// WMI errors propagate: inability to inspect processes must block mutation.
function ProcessExists(const Predicate: String): Boolean;
var
  Processes: Variant;
begin
  InitProcessScanner();
  Processes := WmiServices.ExecQuery('SELECT ProcessId FROM Win32_Process WHERE ' + Predicate);
  Result := Processes.Count > 0;
end;

function BusyReason(): String;
begin
  Result := '';
  try
    if ProcessExists('Name = ''vrserver.exe'' OR Name = ''vrcompositor.exe'' OR Name = ''vrmonitor.exe''') then
      Result := 'Close SteamVR before continuing. Your VR session will not be stopped automatically.'
    else if ProcessExists('Name = ''ALVR Dashboard.exe''') then
      Result := 'Close ALVR Dashboard before continuing.'
    else if ProcessExists('Name = ''vibertemis-host-companion.exe'' OR Name = ''VibertemisManager.App.exe''') then
      Result := 'Exit VibertemisVR Host Manager from its tray icon and stop any standalone host companion before continuing.';
  except
    Result := 'Could not check running applications. Installation cannot safely continue. Restart Windows and retry.';
  end;
end;

function InitializeSetup(): Boolean;
var
  UpdatePid, Reason: String;
  Index, WaitMs, Pid: Integer;
begin
  Result := False;
  UpdatePid := ExpandConstant('{param:UPDATEPID|}');
  if UpdatePid <> '' then
  begin
    for Index := 1 to Length(UpdatePid) do
      if (UpdatePid[Index] < '0') or (UpdatePid[Index] > '9') then Exit;
    Pid := StrToIntDef(UpdatePid, 0);
    if Pid <= 0 then Exit;
    WaitMs := 0;
    try
      while ProcessExists('ProcessId = ' + IntToStr(Pid)) do
      begin
        if WaitMs >= 15000 then
        begin
          SuppressibleMsgBox('The previous manager has not exited. Exit it from the tray and retry.', mbError, MB_OK, IDOK);
          Exit;
        end;
        Sleep(250);
        WaitMs := WaitMs + 250;
      end;
    except
      SuppressibleMsgBox('Could not confirm the previous manager exited. Retry after closing it.', mbError, MB_OK, IDOK);
      Exit;
    end;
  end;
  Reason := BusyReason();
  if Reason <> '' then SuppressibleMsgBox(Reason, mbError, MB_OK, IDOK);
  Result := Reason = '';
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  // Recheck after the wizard, including unattended installs.
  Result := BusyReason();
end;

function InitializeUninstall(): Boolean;
var
  Reason: String;
begin
  Reason := BusyReason();
  if Reason <> '' then SuppressibleMsgBox(Reason, mbError, MB_OK, IDOK);
  Result := Reason = '';
end;

// Inno removes only installed files. runtime/session.json and user state
// survive reinstall/uninstall; never recursively delete {app}.
