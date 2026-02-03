# CLAUDE.md

This is a Burp Suite Extension that adds a new "taborator" tab. This tab is like the collaborator tool, with extra functionality. The core difference is that it attaches the original request/response that triggered a collaborator callback to the taborator interactions. 

## Architecture

- **Main Entry Point**: `src/main/java/burp/BurpExtender.java` - implements `BurpExtension` interface
- **Build System**: Gradle with Groovy DSL, Java 11 compatibility
- **Dependencies**: Montoya API 2025.12 (compile-only), Gson 2.8.5 (runtime, bundled in fat JAR)
- **Extension Pattern**: Single-class extension that initializes through `initialize(MontoyaApi montoyaApi)` method

## Key Development Commands

```bash
./gradlew build    # Build and test the extension
./gradlew jar      # Create the extension JAR file
./gradlew clean    # Clean build artifacts
```

The built JAR file will be in `build/libs/` and can be loaded directly into Burp Suite.

## Extension Loading in Burp

1. Build the JAR using `./gradlew jar`
2. In Burp: Extensions > Installed > Add > Select the JAR file
3. For quick reloading during development: Ctrl/⌘ + click the Loaded checkbox

## Documentation Structure

- See @docs/bapp-store-requirements.md for BApp Store submission requirements
- See @docs/montoya-api-examples.md for code patterns and extension structure  
- See @docs/development-best-practices.md for development guidelines
- See @docs/resources.md for external documentation and links

## Current State

Montoya API rewrite of the original taborator. 
