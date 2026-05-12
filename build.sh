#!/bin/bash
# TelChain Build Script

SRC_DIR="src"
OUT_DIR="out"
MAIN_SERVER="telchain.server.TelChainServer"
MAIN_SENSOR="telchain.client.SensorClient"
MAIN_DASH="telchain.client.DashboardClient"
DIST_DIR="dist"

echo "========================================"
echo "  TelChain - Build Script"
echo "========================================"

# Compilação
echo "[BUILD] Compiling Java sources..."
mkdir -p "$OUT_DIR"
mkdir -p "$DIST_DIR"
find "$SRC_DIR" -name "*.java" | xargs javac -d "$OUT_DIR" -sourcepath "$SRC_DIR"
if [ $? -ne 0 ]; then
    echo "[BUILD] ✗ Compilation failed!"
    exit 1
fi
echo "[BUILD] ✓ Compilation successful!"

# Criação de JARs
echo "[BUILD] Creating JARs..."

# Server JAR
echo "Main-Class: $MAIN_SERVER" > /tmp/server-manifest.mf
jar cfm $DIST_DIR/telchain-server.jar /tmp/server-manifest.mf -C "$OUT_DIR" .
echo "[BUILD] ✓ telchain-server.jar"

# Sensor JAR
echo "Main-Class: $MAIN_SENSOR" > /tmp/sensor-manifest.mf
jar cfm $DIST_DIR/telchain-sensor.jar /tmp/sensor-manifest.mf -C "$OUT_DIR" .
echo "[BUILD] ✓ telchain-sensor.jar"

# Dashboard JAR
echo "Main-Class: $MAIN_DASH" > /tmp/dash-manifest.mf
jar cfm $DIST_DIR/telchain-dashboard.jar /tmp/dash-manifest.mf -C "$OUT_DIR" .
echo "[BUILD] ✓ telchain-dashboard.jar"

echo ""
echo "========================================"
echo "  Build Complete! Usage:"
echo "========================================"
echo "  # Terminal 1 - Start server:"
echo "  java -jar dist/telchain-server.jar"
echo ""
echo "  # Terminal 2 - Start sensor 1:"
echo "  java -jar dist/telchain-sensor.jar localhost 7070 sensor-01 s3cr3t 3000"
echo ""
echo "  # Terminal 3 - Start sensor 2:"
echo "  java -jar dist/telchain-sensor.jar localhost 7070 sensor-02 s3cr3t2 4000"
echo ""
echo "  # Terminal 4 - Start dashboard:"
echo "  java -jar dist/telchain-dashboard.jar localhost 7070 dash-01 d4shp4ss"
echo "========================================"