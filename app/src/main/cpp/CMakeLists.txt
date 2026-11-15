# The pty library behind the app's terminal. See pty.c.
cmake_minimum_required(VERSION 3.22.1)
project(debdroidpty C)

add_library(debdroidpty SHARED pty.c)
target_compile_options(debdroidpty PRIVATE -Wall -Wextra -Werror)

# NOTE: 16 KB pages are required for new apps on android 15 and later, and the
#       flag is harmless on the older devices this project exists for.
target_link_options(debdroidpty PRIVATE "-Wl,-z,max-page-size=16384")
