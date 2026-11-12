; Installer del componente locale di Whisper for WhatsApp Web.
;
; Installa whisper_host.exe per il solo utente corrente (nessun privilegio di
; amministratore), scrive il manifest di native messaging e lo registra per
; Chrome ed Edge in HKCU. Il modello non fa parte dell'installer: lo si sceglie
; o scarica dalla pagina delle opzioni dell'estensione.
;
; Compilazione (dalla radice del repository, dopo PyInstaller):
;   ISCC.exe /DAppVersion=1.1.0 installer\setup.iss

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif
#define AppName "Whisper for WhatsApp Web"
#define HostName "local.wa_whisper.host"
; Origini ammesse dal manifest, già nel formato JSON. Un ID per ogni pubblicazione dell'estensione.
#define ExtOrigins '"chrome-extension://mmfdonlfmjnahpaebjhjlgkpplkfplop/"'

[Setup]
AppId={{023E3E70-C041-4C94-BDC3-CC1A03EEA358}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=whisper-for-whatsapp-web
DefaultDirName={localappdata}\Programs\WhisperForWhatsAppWeb
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\build\installer
OutputBaseFilename=WhisperForWhatsAppWeb-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\host\whisper_host.exe

[Languages]
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
italian.WelcomeLabel2=Installa il componente locale che trascrive i vocali di WhatsApp Web con Whisper, sul tuo computer.%n%nNon servono privilegi di amministratore. Il modello si sceglie dopo, dalla pagina dell'estensione.
english.WelcomeLabel2=Installs the local component that transcribes WhatsApp Web voice messages with Whisper, on your computer.%n%nNo administrator rights needed. You pick the model afterwards, from the extension page.
italian.FinishedLabel=Il componente locale è installato.%n%nTorna al browser: la pagina dell'estensione lo rileva da sola.
english.FinishedLabel=The local component is installed.%n%nGo back to the browser: the extension page detects it automatically.

[Files]
Source: "..\build\dist\whisper_host\*"; DestDir: "{app}\host"; Flags: ignoreversion recursesubdirs createallsubdirs

[Registry]
Root: HKCU; Subkey: "Software\Google\Chrome\NativeMessagingHosts\{#HostName}"; ValueType: string; ValueName: ""; ValueData: "{app}\{#HostName}.json"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Microsoft\Edge\NativeMessagingHosts\{#HostName}"; ValueType: string; ValueName: ""; ValueData: "{app}\{#HostName}.json"; Flags: uninsdeletekey

[UninstallDelete]
Type: files; Name: "{app}\{#HostName}.json"
Type: filesandordirs; Name: "{localappdata}\WhisperForWhatsAppWeb"

[Code]
{ Il componente gira come processo console senza finestre, avviato dal browser:
  Restart Manager non lo sa chiudere, quindi installazione e disinstallazione lo
  terminano prima di toccare i file. Una trascrizione in corso finisce in errore e
  l'estensione la propone di nuovo. }
procedure StopHost();
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM whisper_host.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopHost();
  Result := '';
end;

function InitializeUninstall(): Boolean;
begin
  StopHost();
  Result := True;
end;

function JsonEscape(S: String): String;
begin
  Result := S;
  StringChangeEx(Result, '\', '\\', True);
  StringChangeEx(Result, '"', '\"', True);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Json: String;
begin
  if CurStep = ssPostInstall then
  begin
    Json :=
      '{' + #13#10 +
      '  "name": "{#HostName}",' + #13#10 +
      '  "description": "Whisper for WhatsApp Web - trascrizione locale",' + #13#10 +
      '  "path": "' + JsonEscape(ExpandConstant('{app}\host\whisper_host.exe')) + '",' + #13#10 +
      '  "type": "stdio",' + #13#10 +
      '  "allowed_origins": [{#ExtOrigins}]' + #13#10 +
      '}' + #13#10;
    if not SaveStringToFile(ExpandConstant('{app}\{#HostName}.json'), Json, False) then
      RaiseException('Impossibile scrivere il manifest di native messaging.');
  end;
end;
