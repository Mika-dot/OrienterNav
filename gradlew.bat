@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)
%JAVA_EXE% -version >NUL 2>&1
IF %ERRORLEVEL% NEQ 0 (
  ECHO ERROR: Java 17 is required. Set JAVA_HOME or add java.exe to PATH.
  EXIT /B 1
)
%JAVA_EXE% %JAVA_OPTS% %GRADLE_OPTS% -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
