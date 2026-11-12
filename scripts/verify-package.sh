#!/usr/bin/env bash
# Checks what would be published, after `mvn package`:
#   - every artifact has its jar, sources jar and javadoc jar (Maven Central refuses otherwise)
#   - bloomsight-java has NO runtime dependencies, is Java 11 bytecode, carries its real
#     version, and ships no test classes
#   - the starter carries its auto-configuration and the IDE metadata for bloomsight.*
#   - a plain Java program compiles and runs against the client jar ALONE (no Maven,
#     no other jars), with JAVA11_HOME when given, so Java 11 is proven, not assumed
# Usage: scripts/verify-package.sh            (run from the repository root)
set -euo pipefail

VERSION="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout)"
fail() { echo "verify-package: FAIL: $*" >&2; exit 1; }
ok() { echo "verify-package: ok   $*"; }

mvn -B -q -DskipTests package

for module in bloomsight-java bloomsight-spring-boot-starter; do
	for suffix in "" "-sources" "-javadoc"; do
		f="$module/target/$module-$VERSION$suffix.jar"
		[ -s "$f" ] || fail "missing $f"
	done
	ok "$module: jar, sources and javadoc"
done

CORE="bloomsight-java/target/bloomsight-java-$VERSION.jar"
STARTER="bloomsight-spring-boot-starter/target/bloomsight-spring-boot-starter-$VERSION.jar"

deps="$(mktemp)"
mvn -B -q -pl bloomsight-java dependency:list -DincludeScope=runtime -DoutputFile="$deps" >/dev/null
if grep -qE '^\s+[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:' "$deps"; then
	cat "$deps" >&2
	fail "bloomsight-java has runtime dependencies"
fi
rm -f "$deps"
ok "bloomsight-java has no runtime dependencies"

major="$(javap -v -cp "$CORE" com.bloomscorp.bloomsight.Bloomsight | awk '/major version/ {print $3}')"
[ "$major" = "55" ] || fail "bloomsight-java is class version $major, expected 55 (Java 11)"
ok "bloomsight-java is Java 11 bytecode"

unzip -p "$CORE" com/bloomscorp/bloomsight/bloomsight-java.properties | grep -qx "version=$VERSION" \
	|| fail "bloomsight-java.properties does not say version=$VERSION"
unzip -p "$CORE" META-INF/MANIFEST.MF | grep -q "Automatic-Module-Name: com.bloomscorp.bloomsight" \
	|| fail "no Automatic-Module-Name in the client jar"
if unzip -l "$CORE" | grep -qE 'Test|FakeBlooms'; then fail "test classes in the client jar"; fi
ok "bloomsight-java carries version $VERSION and its module name, and no tests"

unzip -p "$STARTER" META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
	| grep -qx "com.bloomscorp.bloomsight.spring.BloomsightAutoConfiguration" || fail "starter: no AutoConfiguration.imports"
unzip -p "$STARTER" META-INF/spring-configuration-metadata.json | grep -q '"bloomsight.api-key"' \
	|| fail "starter: no configuration metadata for bloomsight.api-key"
ok "starter carries its auto-configuration and IDE metadata"

JHOME="${JAVA11_HOME:-${JAVA_HOME:-}}"
JAVAC="${JHOME:+$JHOME/bin/}javac"
JAVA="${JHOME:+$JHOME/bin/}java"
work="$(mktemp -d)"
cat > "$work/Consumer.java" <<'EOF'
import com.bloomscorp.bloomsight.Bloomsight;
import com.bloomscorp.bloomsight.BloomsightException;
import com.bloomscorp.bloomsight.SendMailRequest;

public class Consumer {
	public static void main(String[] args) {
		try {
			Bloomsight.builder().apiKey("not-a-key").build();
			throw new AssertionError("a bad key was accepted");
		} catch (BloomsightException e) {
			if (!"INVALID_API_KEY".equals(e.getCode())) throw new AssertionError(e.getCode());
		}
		Bloomsight b = Bloomsight.builder().apiKey("blm_test_" + "0".repeat(64)).build();
		SendMailRequest r = SendMailRequest.builder().templateId("t").engineId("e").value("name", "Asha").build();
		if (!b.isTestKey() || r.getData().isEmpty()) throw new AssertionError("client did not build");
		System.out.println(Bloomsight.VERSION);
	}
}
EOF
"$JAVAC" --release 11 -cp "$CORE" -d "$work" "$work/Consumer.java"
printed="$("$JAVA" -cp "$CORE:$work" Consumer)"
[ "$printed" = "$VERSION" ] || fail "consumer printed '$printed', expected $VERSION"
rm -rf "$work"
ok "a Java $("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2}') program runs on the client jar alone"

echo "verify-package: all checks passed for $VERSION"
