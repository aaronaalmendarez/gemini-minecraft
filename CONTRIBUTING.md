# Contributing to Gemini AI Companion

First off, thank you for considering contributing to Gemini AI Companion! It's people like you that make this mod a great tool for everyone.

## Code of Conduct

By participating in this project, you are expected to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check the [Issue Tracker](https://github.com/aaronaalmendarez/gemini-minecraft/issues) to see if the problem has already been reported.

When you are creating a bug report, please include as many details as possible:
* **Use a clear and descriptive title**
* **Describe the exact steps which reproduce the problem**
* **Provide log files** from `.minecraft/logs/`
* **Mention your game and loader version** (e.g., Minecraft 1.21.1, Fabric 0.18.4)

### Suggesting Enhancements

We're always looking for new ideas! Please open a "Feature Request" issue to discuss your proposal.

## Pull Request Process

1. **Fork the repository** and create your branch from `main`.
2. **Follow the existing code style**:
    - Use Java 21 features where appropriate.
    - Maintain the modular structure in `com.aaron.gemini`.
    - Document new functions and complex logic.
3. **Tests**: Ensure your changes don't break existing functionality.
4. **Documentation**: Update the README or other documentation if your changes introduce new features.
5. **Issue link**: Reference the issue your PR addresses in the description.

## Development Setup

The project uses Gradle. You can set it up in your IDE (IntelliJ IDEA recommended):
1. Clone the repo.
2. Select `build.gradle` and "Open as Project".
3. Run `./gradlew genSources` to set up the Fabric environment.

Thank you for your contributions! ❤️
