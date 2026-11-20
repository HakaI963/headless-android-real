cmake_minimum_required(VERSION 3.22.1)
project("headless")

add_library(headless SHARED headless.cpp)

find_library(log-lib log)
target_link_libraries(headless ${log-lib})
