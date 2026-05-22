#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# Configuration
PROJECT_NAME="omnis-mobile"
DEFAULT_INITIAL_VERSION="v0.1.0"
VERSION_BUMP_TYPE="patch" # Can be major, minor, patch
MAIN_BRANCH="main"

# Function to display usage
usage() {
  echo "Usage: $0 [major|minor|patch|vX.Y.Z]"
  echo "  Calculates the next version, creates a git tag, and pushes it."
  echo "  The app version (versionName) in build.gradle.kts is updated automatically."
  echo "  If a version is specified (vX.Y.Z), it will use that exact version."
  exit 1
}

# --- Main script ---

# Ensure we are on the main branch and it's clean
if [ "$(git rev-parse --abbrev-ref HEAD)" != "$MAIN_BRANCH" ]; then
  echo "Error: Not on the $MAIN_BRANCH branch. Please switch to $MAIN_BRANCH before releasing."
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "Error: Working directory is not clean. Please commit or stash your changes."
  exit 1
fi

echo "Updating local repository..."
git pull origin $MAIN_BRANCH

# Parse arguments
if [ "$#" -eq 1 ]; then
  if [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    NEW_VERSION_ARG="$1"
  elif [[ "$1" =~ ^(major|minor|patch)$ ]]; then
    VERSION_BUMP_TYPE="$1"
  else
    usage
  fi
elif [ "$#" -ne 0 ]; then
  usage
fi

# Fetch all tags to ensure we have the latest
echo "Fetching latest tags..."
git fetch origin --tags

# Get the latest version tag
LAST_TAG=$(git describe --tags --abbrev=0 --match "v[0-9]*.[0-9]*.[0-9]*" 2>/dev/null || echo "")

# If no semantic version tags exist, start with the default initial version
if [ -z "$LAST_TAG" ]; then
    echo "No semantic version tags found. Starting with $DEFAULT_INITIAL_VERSION."
    CURRENT_VERSION_SEMVER="0.0.0" # Base for first bump
else
    CURRENT_VERSION_SEMVER=$(echo "$LAST_TAG" | sed 's/^v//')
fi

# Determine the new version
if [ -n "$NEW_VERSION_ARG" ]; then
    NEW_VERSION=$(echo "$NEW_VERSION_ARG" | sed 's/^v//')
    echo "Using specified version: v$NEW_VERSION"
else
    # Split version into major, minor, patch
    IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION_SEMVER"

    case "$VERSION_BUMP_TYPE" in
        major)
            MAJOR=$((MAJOR + 1))
            MINOR=0
            PATCH=0
            ;;
        minor)
            MINOR=$((MINOR + 1))
            PATCH=0
            ;;
        patch)
            PATCH=$((PATCH + 1))
            ;;
        *)
            echo "Invalid bump type: $VERSION_BUMP_TYPE. Must be major, minor, or patch."
            usage
            ;;
    esac
    NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    echo "Bumping $VERSION_BUMP_TYPE version: v$CURRENT_VERSION_SEMVER -> v$NEW_VERSION"
fi

# Update the version in app/build.gradle.kts
# Pattern looks for versionName = "X.Y.Z"
CURRENT_VERSION_IN_GRADLE=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
sed -i "s/versionName = \"${CURRENT_VERSION_IN_GRADLE}\"/versionName = \"${NEW_VERSION}\"/" app/build.gradle.kts

# Also bump versionCode (integer)
CURRENT_VERSION_CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
sed -i "s/versionCode = ${CURRENT_VERSION_CODE}/versionCode = ${NEW_VERSION_CODE}/" app/build.gradle.kts

echo "Updated app/build.gradle.kts to version ${NEW_VERSION} (code ${NEW_VERSION_CODE})"

# Commit the version bump
git add app/build.gradle.kts
git commit -m "chore: bump version to ${NEW_VERSION}"

# Create new tag
NEW_TAG="v$NEW_VERSION"
echo "Creating git tag: $NEW_TAG"
git tag "$NEW_TAG" -m "$PROJECT_NAME Release $NEW_VERSION"

# Push new tag and commit
echo "Pushing commit and tag to origin..."
git push origin $MAIN_BRANCH
git push origin "$NEW_TAG"

# Get repository owner and name
REPO_URL=$(git config --get remote.origin.url)
REPO_OWNER=$(echo "$REPO_URL" | sed -E 's/.*github.com[:/]([^/]+)\/.*/\1/')
REPO_NAME=$(echo "$REPO_URL" | sed -E 's/.*github.com[:/][^/]+\/([^/.]+)(\.git)?/\1/')
REPO_PATH="$REPO_OWNER/$REPO_NAME"

# Create a release on GitHub using gh CLI (if available)
if command -v gh &> /dev/null; then
    echo "Creating GitHub Release..."
    gh release create "$NEW_TAG" --title "$PROJECT_NAME $NEW_TAG" --generate-notes
else
    echo "Warning: GitHub CLI (gh) not found. Tag pushed, but Release not created automatically."
fi

echo "Release process initiated with tag $NEW_TAG. GitHub Actions will build and attach the APK."
