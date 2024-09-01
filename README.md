## Head Extractor

Head Extractor is a tool and library to extract the skin hashes from the player heads in a Minecraft world.

This is accomplished somewhat inefficiently by searching chunk NBT, player data NBT, and entity NBT for lists of
Compound tags that contain a String tag named `Value`.\
In addition, `mcfunction` and `json` files in data packs are scanned for Base64 encoded player profiles.

The skin hash is then extracted from the Base64 encoded player profile.

### Compiling
1. Clone the repository
2. Run `./gradlew build` or `gradlew.bat build`
3. Tool is located at `build/libs/HeadExtractor-<VERSION>-all.jar`

### Running
`java -jar HeadExtractor-<VERSION>-all.jar [OPTIONS] <WORLD DIRECTORIES>`

Options:
- `--exclude-entities`: Exclude heads carried by entities
- `--exclude-region`: Exclude heads placed in the world and in containers
- `--exclude-playerdata`: Exclude heads in players' inventories
- `--exclude-datapacks`: Exclude base64-encoded player profiles in .json or .mcfunction files in datapacks

There is also a corresponding --include option for each of the above. The default behavior is to include all heads.

Skin hashes are saved to `custom-skulls.yml`. 

### Library usage
You can include this library in your project from [jitpack.io](https://jitpack.io/)!

- Gradle:
  - Add `maven { url 'https://jitpack.io' }` to the end of your repositories section
  - Add the dependency `implementation 'com.github.<user>:HeadExtractor:<version>'`
    - Replace `<user>` with the owner of the fork you would like to use
    - Replace `<version>` with the tag of the GitHub Release you would like to use
      - You can also use `main-SNAPSHOT` to take the latest commit from `main`
- Maven:
  - Add the repository to the end of your repositories section:
    ```xml
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
    ```
  - Add the dependency:
    ```xml
    <dependency>
	    <groupId>com.github.User</groupId>
	    <artifactId>HeadExtractor</artifactId>
	    <version>Version</version>
    </dependency>
    ```
    - Replace `User` with the owner of the fork you would like to use
    - Replace `Version` with the tag of the GitHub Release you would like to use
        - You can also use `main-SNAPSHOT` to take the latest commit from `main`

You can also include the compiled jar as a library using your preferred method.

Once you've included the library, all you need to do is call 
`me.amberichu.headextractor.HeadExtractor#extractHeads(Set<Path> worldPaths, boolean includeEntities,
boolean includeRegion, boolean includePlayerData, boolean includeDataPacks)`!
