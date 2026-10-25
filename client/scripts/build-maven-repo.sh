#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: build-maven-repo.sh VERSION}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$ ]]; then
  echo "Invalid version: $version" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
bash scripts/build.sh

artifact_root="dist/maven-repo/cloud/lunarsky/objectstore-client"
version_dir="$artifact_root/$version"
mkdir -p "$version_dir"
base="objectstore-client-$version"
cp dist/objectstore-client.jar "$version_dir/$base.jar"
jar --create --file "$version_dir/$base-sources.jar" \
  -C src/main/java . -C dist/classes META-INF/LICENSE
jar --create --file "$version_dir/$base-javadoc.jar" \
  -C dist/javadoc . -C dist/classes META-INF/LICENSE

cat > "$version_dir/$base.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>cloud.lunarsky</groupId>
  <artifactId>objectstore-client</artifactId>
  <version>$version</version>
  <name>ObjectStore Java Client</name>
  <description>JDK-only client for S3-compatible object storage.</description>
  <url>https://github.com/LunarSkyOSS/ObjectStore</url>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/license/mit</url>
    </license>
  </licenses>
  <developers>
    <developer><name>Justin Haag</name></developer>
  </developers>
  <scm>
    <connection>scm:git:https://github.com/LunarSkyOSS/ObjectStore.git</connection>
    <url>https://github.com/LunarSkyOSS/ObjectStore</url>
  </scm>
</project>
EOF

mapfile -t versions < <(find "$artifact_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V)
latest="${versions[${#versions[@]}-1]}"
{
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '<metadata>'
  printf '  <groupId>cloud.lunarsky</groupId>\n  <artifactId>objectstore-client</artifactId>\n'
  printf '  <versioning>\n    <latest>%s</latest>\n    <release>%s</release>\n    <versions>\n' "$latest" "$latest"
  for item in "${versions[@]}"; do printf '      <version>%s</version>\n' "$item"; done
  printf '    </versions>\n    <lastUpdated>%s</lastUpdated>\n  </versioning>\n</metadata>\n' "$(date -u +%Y%m%d%H%M%S)"
} > "$artifact_root/maven-metadata.xml"

for file in "$version_dir"/* "$artifact_root/maven-metadata.xml"; do
  [[ "$file" == *.md5 || "$file" == *.sha1 || "$file" == *.sha256 ]] && continue
  md5sum "$file" | cut -d ' ' -f 1 > "$file.md5"
  sha1sum "$file" | cut -d ' ' -f 1 > "$file.sha1"
  sha256sum "$file" | cut -d ' ' -f 1 > "$file.sha256"
done

echo "Built $version_dir"
