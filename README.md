# README
http://192.168.100.10/api/touch_event_sequence_1

## GitHub Actions APK

The `Build APK` workflow builds a debug APK on every branch push and pull
request, and can also be started manually from the Actions tab. Download
`app-debug` from the completed workflow run's artifacts to get `app-debug.apk`.

Pushing a version tag such as `v1.0.0` also creates a GitHub Release with the
built `app-debug.apk` attached. Create and push a tag with:

```sh
git tag v1.0.0
git push origin v1.0.0
```
