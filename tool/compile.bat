set COMMON=-I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32"

zig cc -shared -target x86_64-windows -g0 -Wl,--strip-all %COMMON% -o ./../assets/libs/tool64.dll tool.zig
zig cc -shared -target x86_64-macos -g0 -Wl,--strip-all -fdeclspec %COMMON% -o ./../assets/libs/libtoolarm64.dylib tool.zig
zig cc -shared -target x86_64-macos -g0 -Wl,--strip-all -fdeclspec %COMMON% -o ./../assets/libs/libtool64.dylib tool.zig
zig cc -shared -target x86_64-linux -g0 -Wl,--strip-all -fdeclspec %COMMON% -o ./../assets/libs/libtool64.so tool.zig
rem pause