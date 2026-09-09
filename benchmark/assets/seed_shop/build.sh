#!/usr/bin/env bash
# Unix 一键编译并运行演示入口
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out $(find src -name '*.java')
echo "BUILD OK"
java -cp out com.shop.web.App
