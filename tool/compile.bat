zig cc -shared -target x86_64-windows -v -I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -o ./../assets/libs/tool64.dll tool.zig
zig cc -shared -target x86_64-macos -fdeclspec -I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -o ./../assets/libs/libtoolarm64.dylib tool.zig
zig cc -shared -target x86_64-macos -fdeclspec -I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -o ./../assets/libs/libtool64.dylib tool.zig
zig cc -shared -target x86_64-linux -fdeclspec -I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -o ./../assets/libs/libtool64.so tool.zig
rem pause