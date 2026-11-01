# Security policy

## Supported versions

Security fixes are made in the latest version of Files on the `main` branch
and in the next official build. Older builds are not updated; please update to
the latest version before reporting.

Official builds are signed by CLP Studio with the certificate whose SHA-256
fingerprint is `febc1f7c94c20f90ba40ba1cb1d58c9c6209ee430b98a60a479852e99e8b353e`
(see `README.md`). Builds signed with other keys, including builds of forks,
are not covered by this policy.

## Reporting a vulnerability

Please report vulnerabilities **privately**. Do not open a public issue, pull
request or discussion for a security problem.

- Preferred: use GitHub's private vulnerability reporting on this repository
  (Security tab > "Report a vulnerability").
- Alternative: email the contact address shown on the CLP Studio GitHub
  organisation profile, https://github.com/clpsudio, with "Files security" in
  the subject.

Please include:

- the app version and the Android version and device you tested on;
- a description of the problem and its impact;
- steps to reproduce, or a proof of concept;
- whether the issue is already public anywhere.

Do not include other people's personal data or files in your report. Only test
on devices and data that you own.

## What happens next

CLP Studio will confirm that the report was received, investigate it, and keep
you informed. When a fix is ready it is released in a new build, and the issue
may then be disclosed in a GitHub security advisory. Please keep the details
confidential until a fix is available. If you want to be credited, say so in
your report.

## Scope

In scope: the source code in this repository and official CLP Studio builds,
for example problems with the app lock, the vault (encrypted files), archive
extraction (such as path traversal), the file provider and documents provider,
APK installation, or permission handling.

Out of scope: vulnerabilities in third-party libraries that are not caused by
how Files uses them (please report those to the library's maintainers, and let
us know so we can update), devices that are rooted or running modified
Android, and problems that need physical access to an unlocked phone.
