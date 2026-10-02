set COMMON=-I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -std=c++23 -O3 -fno-exceptions -fno-rtti -fdeclspec -w

zig c++ -shared -target x86_64-windows -Wl,--strip-all %COMMON% -o ./../assets/libs/tool64.dll tool.cpp
zig c++ -shared -target aarch64-macos -Wl,-dead_strip %COMMON% -o ./../assets/libs/libtoolarm64.dylib tool.cpp
zig c++ -shared -target x86_64-macos -Wl,-dead_strip %COMMON% -o ./../assets/libs/libtool64.dylib tool.cpp
zig c++ -shared -target x86_64-linux -Wl,--strip-all %COMMON% -o ./../assets/libs/libtool64.so tool.cpp