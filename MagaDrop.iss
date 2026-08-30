[Setup]
AppName=MagaDrop
AppVersion=2.0.0
DefaultDirName={pf}\MagaDrop
DefaultGroupName=MagaDrop
OutputDir=output
OutputBaseFilename=MagaDropSetup
SetupIconFile=file.ico

[Files]
Source: "MagaDrop.exe"; DestDir: "{app}"
Source: "file.ico"; DestDir: "{app}"
Source: "THIRD_PARTY_LICENSES.md"; DestDir: "{app}"
Source: "jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs
Source: "web\*"; DestDir: "{app}\web"; Flags: recursesubdirs

[Icons]
Name: "{group}\MagaDrop"; Filename: "{app}\MagaDrop.exe"
Name: "{commondesktop}\MagaDrop"; Filename: "{app}\MagaDrop.exe"

[Run]
Filename: "{app}\MagaDrop.exe"; Description: "Abrir MagaDrop"; Flags: nowait postinstall skipifsilent
