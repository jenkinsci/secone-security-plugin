/*
 See the documentation for more options:
 https://github.com/jenkins-infra/pipeline-library/
*/
buildPlugin(
  forkCount: '1C', 
  useContainerAgent: true,
  configurations: [
    [platform: 'linux', jdk: 11], // use 'docker' if you have containerized tests
    [platform: 'windows', jdk: 11],
])
