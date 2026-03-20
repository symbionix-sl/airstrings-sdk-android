.PHONY: setup config seed build clean

# Full setup: generate config, seed data, build
setup: config seed build

# Extract public key → DemoConfig.generated.kt
config:
	bash generate-config.sh

# Seed demo strings and publish bundles via the API
seed:
	bash seed.sh

# Build the demo APK
build:
	cd .. && ./gradlew :demo:assembleDebug

# Remove build artifacts
clean:
	cd .. && ./gradlew :demo:clean
