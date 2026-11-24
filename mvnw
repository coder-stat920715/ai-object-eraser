#!/bin/sh
# Minimal Maven wrapper: downloads the Maven version named in .mvn/wrapper/maven-wrapper.properties
# into ~/.m2/wrapper/dists on first use, then delegates to it. Needs curl or wget, and tar.
set -e
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | tr -d '\r')
[ -n "$URL" ] || { echo "distributionUrl missing in $PROPS" >&2; exit 1; }
NAME=$(basename "$URL" -bin.tar.gz)
DIST="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/$NAME"

if [ ! -x "$DIST/bin/mvn" ]; then
  echo "Downloading Maven ($NAME) ..." >&2
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL" -o "$TMP/maven.tgz"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP/maven.tgz" "$URL"
  else
    echo "Install curl or wget to use the Maven wrapper." >&2; exit 1
  fi
  mkdir -p "$DIST"
  tar -xzf "$TMP/maven.tgz" -C "$DIST" --strip-components=1
fi

exec "$DIST/bin/mvn" "$@"
