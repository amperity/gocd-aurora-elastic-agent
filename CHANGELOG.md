Change Log
==========

All notable changes to this project will be documented in this file. This change
log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

## [0.2.1] - 2020-09-24
### Changed
- Run the `init-script` in the same process as the agent to make it easier to
  e.g. source environment variables.
  [#28](https://github.com/amperity/gocd-aurora-elastic-agent/pull/28)

## [0.2.0] - 2020-05-08
### Added
- Optionally allow specifying agent environments
  [#27](https://github.com/amperity/gocd-aurora-elastic-agent/pull/27)
- Added release instructions
  [#26](https://github.com/amperity/gocd-aurora-elastic-agent/pull/26)

### Changed
- Update agent wrapper port range to not be in default Mesos port range
  [#24](https://github.com/amperity/gocd-aurora-elastic-agent/pull/24)
- Style improvements for the cluster status page
  [#23](https://github.com/amperity/gocd-aurora-elastic-agent/pull/23)
- Increase maximum allowed memory/disk
  [#19](https://github.com/amperity/gocd-aurora-elastic-agent/pull/19)

### Fixed
- Require agents to be in the requested cluster
  [#20](https://github.com/amperity/gocd-aurora-elastic-agent/pull/20)

## [0.1.1] - 2019-10-16
### Fixed
- Fix some places where the version hadn't been updated to 19.8.0 correctly.

## [0.1.0] - 2019-10-08
### Added
- Initial plugin release.

[Unreleased]: https://github.com/amperity/gocd-aurora-elastic-agent/compare/v0.2.0...HEAD
[0.2.1]: https://github.com/amperity/gocd-aurora-elastic-agent/releases/tag/v0.2.1
[0.2.0]: https://github.com/amperity/gocd-aurora-elastic-agent/releases/tag/v0.2.0
[0.1.1]: https://github.com/amperity/gocd-aurora-elastic-agent/releases/tag/v0.1.1
[0.1.0]: https://github.com/amperity/gocd-aurora-elastic-agent/releases/tag/v0.1.0
