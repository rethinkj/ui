# Contributing

Thanks for helping. Bug fixes, documentation and small improvements are welcome as pull requests right away. For a new feature or a larger change, open an issue first so we can agree on the direction before you spend time on it.

The rules for working on the repository are in [AGENTS.md](AGENTS.md): targets, modules, build, tests and documentation. They apply to people and coding agents alike. In short:

- Branch from `main` and build with `mvn verify` (JDK 25, Maven 3.9+). Run `mvn spotless:apply` before you commit.
- Title the pull request in [Conventional Commits](https://www.conventionalcommits.org/) form, for example `fix(core): keep the caret after an undo`. It becomes the squashed commit.
- Say in a few sentences what changed and why. If it resolves an issue completely, write `Fixes #<number>`; if it resolves part of one, write `Part of #<number>`.
- A change users of the library notice adds a file under `changelog/unreleased/` and updates the documentation page it concerns.
- Add a test when behavior a user can notice could break, and no more than that.

The workflows of a pull request from a fork start after a maintainer has looked at it, so the first checks can take a little while.

To report a security problem, follow [SECURITY.md](SECURITY.md) instead of opening an issue.

By contributing you agree that your contribution is licensed under the project's [MIT license](LICENSE).
