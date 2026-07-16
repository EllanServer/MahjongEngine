# Contributing

Thanks for helping improve MahjongPaper.

## Before You Start

- Search existing issues and pull requests first.
- Keep each pull request focused on one change.
- Prefer small, reviewable commits.

## Local Setup

1. Fork and clone the repository.
2. Use JDK 21+ for the default build. The released jar is built with Java 21 bytecode and `api-version: 1.20`, so the server runtime must use Java 21 or newer while one jar continues to cover Paper/Folia 1.20.1 through 26.2.
3. Build the project:

```powershell
.\gradlew.bat build
```

To validate source/API compatibility against Paper 26.2:

```powershell
.\gradlew.bat test "-PmahjongPaperDevBundle=26.2-rc-2.build.9-alpha" -PmahjongJavaToolchain=25 -PmahjongJavaTarget=25
```

This command is only a high-version source/API compatibility check. Release artifacts use the Java 21 default target so the distributed jar remains compatible with the whole supported server range.

## Development Workflow

1. Create a branch from `dev`.
2. Implement your change.
3. Run tests:

```powershell
.\gradlew.bat test
```

4. If behavior changes, update docs and user-facing text.
5. Open a pull request to `dev` with a clear summary.

## Coding Guidelines

- Follow existing code style and naming conventions.
- Avoid unrelated refactors in functional bug-fix PRs.
- Keep behavior changes explicit and tested.
- Prefer backwards-compatible changes when possible.

## Commit Messages

Use concise, descriptive messages, for example:

- `fix: handle null seat during restore`
- `refactor: extract shared reaction helpers`
- `docs: update setup instructions`

## Reporting Bugs

Use the Bug Report template and include:

- Steps to reproduce
- Expected vs actual behavior
- Server and plugin version
- Logs or stack traces if available

## Security Issues

Do not open public issues for vulnerabilities.
Report privately via:

- <https://github.com/Arbousier1/MahjongEngine/security/advisories/new>
