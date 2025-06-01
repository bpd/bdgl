
.PHONY: all loader run

CC = gcc

DEADCODE = -ffunction-sections -Wl,--gc-sections

# -fvisibility=hidden => any symbols not explicitly defined as exported are private
# -lGL
#
loader:
	$(CC) -std=c11 -pedantic -Wall -O3 -lglfw -o dist/bdgl-loader src/loader_main.c

run: loader
	./dist/bdgl-loader

gen:
	java parser/GLParser.java
