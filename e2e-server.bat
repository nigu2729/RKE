@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17
set RK_LOG_ENABLED=1
call gradlew.bat runServer --console=plain
