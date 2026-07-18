# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.22.1-g37088a8)

# We name the project and the target for the ExternalProject_Add() call
# to something that will highlight to the user what we are working on if
# something goes wrong and an error message is produced.

project(kleidiai_download-populate NONE)



include(ExternalProject)
ExternalProject_Add(kleidiai_download-populate
                     "UPDATE_DISCONNECTED" "False" "URL" "https://github.com/ARM-software/kleidiai/releases/download/v1.24.0/kleidiai-v1.24.0-src.tar.gz" "URL_HASH" "MD5=2f02ebe29573d45813e671eb304f2a00"
                    SOURCE_DIR          "/home/user-x/projects/aiope/core-inference/.cxx/Release/1z2v1q04/arm64-v8a/_deps/kleidiai_download-src"
                    BINARY_DIR          "/home/user-x/projects/aiope/core-inference/.cxx/Release/1z2v1q04/arm64-v8a/_deps/kleidiai_download-build"
                    CONFIGURE_COMMAND   ""
                    BUILD_COMMAND       ""
                    INSTALL_COMMAND     ""
                    TEST_COMMAND        ""
                    USES_TERMINAL_DOWNLOAD  YES
                    USES_TERMINAL_UPDATE    YES
)


