@echo off
rem ===================================================================
rem  peek_quarry 构建脚本
rem
rem  用法：dev.bat build / runClient / runServer / clean / --status
rem
rem  仓库里不写死任何机器路径。机器相关的 JAVA_HOME / GRADLE_USER_HOME
rem  请放在同目录下的 local.env.bat 里（已在 .gitignore 中，不会被提交）：
rem
rem      set "JAVA_HOME=D:\jdk\zulu25"
rem      set "GRADLE_USER_HOME=D:\gradle-home"
rem
rem  JAVA_HOME 必须是 **Java 25** —— RFG 2.0.4 的 class 文件版本是 69.0，
rem  用 Java 17/21 启动会直接 UnsupportedClassVersionError。
rem  另外还需要一个 JDK 8 供 toolchain 编译，见 README。
rem ===================================================================
setlocal

if exist "%~dp0local.env.bat" call "%~dp0local.env.bat"

if "%JAVA_HOME%"=="" (
    echo [dev.bat] 警告：JAVA_HOME 未设置。RFG 需要 Java 25 才能启动 Gradle。
    echo [dev.bat]        请创建 local.env.bat 并写入 set "JAVA_HOME=..."
)

call "%~dp0gradlew.bat" %*
set "EXITCODE=%ERRORLEVEL%"

endlocal & exit /b %EXITCODE%
