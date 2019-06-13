
## Dependencies

    $ yarn
    $ react-native link realm
    $ react-native link rn-apple-healthkit
    $ react-native link react-native-camera
    $ react-native link @mapbox/react-native-mapbox-gl
    $ react-native link react-native-gesture-handler


## Run JS Compiler

    $ shadow-cljs watch app


## Start metro bundler with more appropriate mem settings

    $ node --expose-gc --max_old_space_size=4096 ./node_modules/react-native/local-cli/cli.js start --reset-cache


## Creating Release Bundle

    $ shadow-cljs release app
    $ node --expose-gc --max_old_space_size=4096 ./node_modules/react-native/local-cli/cli.js bundle --entry-file app/index.js --platform ios --dev false --bundle-output ios/main.jsbundle --assets-dest ios


## Running on Android

    $ adb kill-server
    $ adb start-server
    $ adb devices

    $ react-native run-android