' Inicia o Central de Cameras sem janela de console.
' O caminho e resolvido a partir da propria pasta do VBS, entao o projeto
' pode ser movido para outro lugar sem editar este arquivo.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
ps1  = base & "\CameraViewer.ps1"
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & ps1 & """", 0, False
