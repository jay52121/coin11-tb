Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

baseDir = fso.GetParentFolderName(WScript.ScriptFullName)
cmd = "cmd /c """ & baseDir & "\start_tjb_gui_background.bat"""

shell.Run cmd, 0, False
