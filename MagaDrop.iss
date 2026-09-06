[Setup]
AppId={{2D94447B-C613-46E8-AE35-23DA76DE965B}
AppName=MagaDrop
AppVersion=3.0.0-preview.4
AppVerName=MagaDrop 3 Preview 4
AppPublisher=MagaDrop
VersionInfoVersion=3.0.0.4
VersionInfoProductVersion=3.0.0.4
DefaultDirName={localappdata}\Programs\MagaDrop
DefaultGroupName=MagaDrop
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
OutputDir=output
OutputBaseFilename=MagaDropSetup-v3-preview
SetupIconFile=file.ico

[Files]
Source: "MagaDrop.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "file.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "THIRD_PARTY_LICENSES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "web\*"; DestDir: "{app}\web"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na área de trabalho"; GroupDescription: "Atalhos:"; Flags: checkedonce

[Icons]
Name: "{autoprograms}\MagaDrop"; Filename: "{app}\MagaDrop.exe"; WorkingDir: "{app}"; IconFilename: "{app}\file.ico"
Name: "{autodesktop}\MagaDrop"; Filename: "{app}\MagaDrop.exe"; WorkingDir: "{app}"; IconFilename: "{app}\file.ico"; Tasks: desktopicon

[InstallDelete]
Type: files; Name: "{autoprograms}\MagaDrop 3 Preview.lnk"
Type: files; Name: "{autodesktop}\MagaDrop 3 Preview.lnk"

[Run]
Filename: "{app}\MagaDrop.exe"; WorkingDir: "{app}"; Description: "Abrir MagaDrop"; Flags: nowait postinstall skipifsilent
