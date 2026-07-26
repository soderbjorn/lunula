# Lunula UI Toolkit

Lunula is a UI toolkit.

## Introduction

Lunula is a UI toolkit that I built primarily for web-based applications. It supports flexible layouts with tabs and windows, themes and more. I use it for the following projects:

## [Lunamux](https://www.lunamux.dev)

A macOS terminal built for working alongside AI agents: tabs, windows and a
built-in multiplexing session server. Sessions can be viewed as a flat 2D layout or
lifted into a 3D world you fly through. Native Android and iOS apps connect to the same
server, so you can remotely control your sessions from your phone.

## [Lunicle](https://www.lunicle.dev)

An issue tracker you host yourself. Boards, sprints and epics, plus an MCP server so agents can work on the same issues as everyone else. I use it to steer the development of my hobby projects.

This is a fast-moving, agent-first software development project. If I put too much detail here, it would quickly become obsolete. If you want specifics about the features, source code and the architecture, ask your agent!

## Tech stack

This is mostly written in Kotlin, which i use anywhere I can, because I really like the language, and Kotlin Multiplatform makes it easy to share code (when needed) across Mac, server, web, iOS and Android. I however do **not** use Compose Multiplatform because I want each platform to have a native UI.

In projects on multiple platforms, I try to have common view models across all clients that expose a single state object per screen/view, with thin
wrappers where needed on each platform. I also re-use the Kotlin networking layer across all platforms.

## Author

[Robert Söderbjörn](https://www.soderbjorn.se) is the creator and maintainer of this project. If you would like to contribute, you are more than welcome! You can reach out at lunula@soderbjorn.se. 

## Development

We use the [Lunicle issue tracker](https://issues.lunicle.dev/?projectId=5) for managing development. You can see all issues without signing in. Contact me if you would like edit rights to the board so that you can create, move and comment on tickets and to add pull requests on GitHub. Before embarking on huge re-work (rather than bug fixes or small features), you might want to talk to me first. I'm very open to significant changes as well, I just want us to agree on the UX and make sure it's done in a way that fits the vision.

## License

Lunicle is released under the [MIT License](LICENSE).

Third-party dependencies are used under their respective licenses.
