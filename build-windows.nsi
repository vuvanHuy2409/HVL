Unicode true
!include "MUI2.nsh"

!ifndef APP_NAME
  !define APP_NAME "HVL"
!endif
!ifndef APP_VERSION
  !define APP_VERSION "1.0.0"
!endif
!ifndef STAGE_DIR
  !error "STAGE_DIR is required"
!endif
!ifndef OUTPUT_FILE
  !error "OUTPUT_FILE is required"
!endif

Name "${APP_NAME}"
Caption "${APP_NAME} ${APP_VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$PROGRAMFILES64\HVL"
InstallDirRegKey HKLM "Software\HVL" "InstallDir"
RequestExecutionLevel admin
; FLAC is already compressed. Non-solid zlib blocks keep the compiler and
; installer memory footprint bounded when the bundled library is close to 2 GB.
SetCompressor zlib
SetDatablockOptimize on
CRCCheck on
BrandingText "HVL"
VIProductVersion "1.0.0.0"
VIAddVersionKey "ProductName" "HVL"
VIAddVersionKey "CompanyName" "HVL"
VIAddVersionKey "FileDescription" "HVL music player"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"

!define MUI_ABORTWARNING
!define MUI_ICON "${STAGE_DIR}/HVL.ico"
!define MUI_UNICON "${STAGE_DIR}/HVL.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\runtime\bin\javaw.exe"
!define MUI_FINISHPAGE_RUN_PARAMETERS "-jar $\"$INSTDIR\app\HVLPlayer.jar$\""
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Vietnamese"

Section "HVL" SEC_MAIN
  SetOutPath "$INSTDIR\app"
  File /r "${STAGE_DIR}/app/*"

  SetOutPath "$INSTDIR\runtime"
  File /r "${STAGE_DIR}/runtime/*"

  SetOutPath "$INSTDIR"
  File "${STAGE_DIR}/HVL.ico"

  WriteRegStr HKLM "Software\HVL" "InstallDir" "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\HVL"
  CreateShortcut "$DESKTOP\HVL.lnk" "$INSTDIR\runtime\bin\javaw.exe" '-jar "$INSTDIR\app\HVLPlayer.jar"' "$INSTDIR\HVL.ico" 0 SW_SHOWNORMAL
  CreateShortcut "$SMPROGRAMS\HVL\HVL.lnk" "$INSTDIR\runtime\bin\javaw.exe" '-jar "$INSTDIR\app\HVLPlayer.jar"' "$INSTDIR\HVL.ico" 0 SW_SHOWNORMAL
  CreateShortcut "$SMSTARTUP\HVL.lnk" "$INSTDIR\runtime\bin\javaw.exe" '-jar "$INSTDIR\app\HVLPlayer.jar" --background' "$INSTDIR\HVL.ico" 0 SW_SHOWNORMAL
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\HVL.lnk"
  Delete "$SMSTARTUP\HVL.lnk"
  Delete "$SMPROGRAMS\HVL\HVL.lnk"
  RMDir "$SMPROGRAMS\HVL"
  DeleteRegKey HKLM "Software\HVL"
  RMDir /r "$INSTDIR"
SectionEnd
