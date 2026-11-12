# rethinkj UI

A native, GPU-rendered UI framework for Java, modelled on [rune](https://github.com/anomalyco/rune) (Rust, MIT). Rune is the reference for scope and concepts: its own renderer, text, layout and animation, plus a headless mode in which an agent can render to PNG, dump the element tree and drive an app with scripted input.

Status: bootstrap. The Maven structure exists; there is no Java code yet.

- Repository: https://github.com/rethinkj/ui
- License: MIT

## Build

Requires JDK 25 and Maven 3.9+.

```sh
mvn verify                # build and test everything
mvn -pl kit -am verify    # one module plus the modules it depends on
```

## Modules

| Directory | Artifact           | Base package          | Purpose                    |
| --------- | ------------------ | --------------------- | -------------------------- |
| `core/`   | `rethinkj-ui-core` | `com.rethinkj.ui`     | The framework itself       |
| `kit/`    | `rethinkj-ui-kit`  | `com.rethinkj.ui.kit` | Components built on `core` |

All artifacts share the groupId `com.rethinkj.ui`.

## Conventions

- A new module gets the directory `<name>/`, the artifactId `rethinkj-ui-<name>` and the base package `com.rethinkj.ui.<name>`, and is listed under `<modules>` in the root `pom.xml`.
- Shared build configuration (Java release, encoding, plugin and dependency versions) belongs in the root `pom.xml`. Module POMs declare only what is specific to them.
- Dependencies point one way: `kit` depends on `core`, never the reverse.
- The version is repeated in every module's `<parent>` block. Change it with `mvn versions:set -DnewVersion=<version>`, not by editing one POM.

## Changes

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <summary>`, for example `feat(core): add text shaping`. The scope is the module when the change stays in one. A breaking change gets `!` before the colon.
- Keep commit messages short: a subject line of at most 72 characters, and a body only when the diff doesn't show why. No lists of changed files and no test or verification reports.
- For a change that users of the library can notice (new or changed API, changed behaviour, a fix, a raised requirement), add `changelog/unreleased/<YYYY-MM-DD>-<slug>.md` with one top-level bullet written for those users. These files are folded into `CHANGELOG.md` at release.
- Don't add files that describe your own work: no `VALIDATION.md`, `SUMMARY.md`, plans, reports or review notes. What changed and why goes in the commit message; anything longer goes in the pull request description.

## This repo is public

Everything pushed stays public in history. Before committing, run `git diff --cached | grep -nE '/home/|/Users/|/private/var/|\.ts\.net|ses_[A-Za-z0-9]{20}'` and keep these out:

- secrets, tokens and credential file contents;
- home folder and temp paths, machine names and tailnet hostnames. Write repo-relative paths, and use `host.example.ts.net` in tests;
- session IDs, chat or Slack links, and quotes from private sessions, chats or calls;
- teammates' names attached to their plans, opinions or internal discussions, and private repos or branches;
- quotes of, or citations to, internal guides and rules. Say what the code does and why on its own terms;
- agent handoff notes (`REVIEW-TO-PARENT.md` and similar), raw evidence, logs and benchmark dumps;
- generated videos and strips nothing links to. Commit the stills a doc shows.
