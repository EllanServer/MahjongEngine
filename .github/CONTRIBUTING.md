# Contributing

Thanks for helping improve MahjongPaper.

## Before You Start

- Search existing issues and pull requests first.
- Keep each pull request focused on one change.
- Prefer small, reviewable commits.

## Local Setup

1. Fork and clone the repository.
2. Use JDK 25 for the default build. The server-facing modules and released plugin target Paper/Folia 26.2 and Java 25; the portable core, rule SPI, TCK, and external rule packs remain Java 21.
3. Build the project:

```powershell
.\gradlew.bat build
```

The default build is the Paper 26.2 compatibility check and produces the same classfile targets used by the release workflow.

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
