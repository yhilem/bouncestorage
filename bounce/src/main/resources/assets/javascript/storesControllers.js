var storesControllers = angular.module('storesControllers', ['bounce']);

storesControllers.controller('CreateStoreCtrl', ['$scope', '$rootScope',
  '$location', '$routeParams', 'ObjectStore',
  function ($scope, $rootScope, $location, $routeParams, ObjectStore) {
    $scope.actions = {};
    $scope.provider = {};
    $scope.providers = bounceConstants.providers;

    if (typeof($routeParams.objectStoreId) === 'string') {
      $scope.edit = true;
      ObjectStore.get({ id:$routeParams.objectStoreId },
        function(result) {
          $scope.nickname = result.nickname;
          for (var i = 0; i < $scope.providers.length; i++) {
            if ($scope.providers[i].value === result.provider) {
              $scope.provider = $scope.providers[i];
            }
          }
          $scope.provider.identity = result.identity;
          $scope.provider.credential = result.credential;
          $scope.provider.region = result.region;
          $scope.provider.endpoint = result.endpoint;
      });
    }

    $scope.actions.submitNewStore = function() {
      var newStore = { nickname: $scope.provider.nickname,
                       provider: $scope.provider.value,
                       identity: $scope.provider.identity,
                       credential: $scope.provider.credential,
                       region: $scope.provider.region,
                       endpoint: $scope.provider.endpoint
                     };
      ObjectStore.save(newStore, function (successStore) {
        $rootScope.$emit('addedStore', successStore);
        $location.path('/stores/' + successStore.id);
      });
    };

    $scope.actions.updateStore = function() {
      ObjectStore.update({
        id: $routeParams.objectStoreId,
        nickname: $scope.provider.nickname,
        provider: $scope.provider.value,
        identity: $scope.provider.identity,
        credential: $scope.provider.credential,
        region: $scope.provider.region,
        endpoint: $scope.provider.endpoint
      }, function(res) {
        $location.path('/stores');
      }, function(error) {
        console.log(error);
      });
    };

    $scope.actions.cancelEdit = function() {
      $location.path('/stores');
    };
}]);

function findStore(stores, id) {
  for (var i = 0; i < stores.length; i++) {
    if (stores[i].id === id) {
      return stores[i];
    }
  }
  return undefined;
}

function createNewVirtualContainer(store, container) {
  return { cacheLocation: { blobStoreId: -1,
                            containerName: '',
                            copyDelay: '',
                            moveDelay: ''
                          },
           originLocation: { blobStoreId: store.id,
                             containerName: container.name,
                             copyDelay: '',
                             moveDelay: ''
                           },
           archiveLocation: { blobStoreId: -1,
                              containerName: '',
                              copyDelay: '',
                              moveDelay: ''
                            },
           migrationTargetLocation: { blobStoreId: -1,
                                      containerName: '',
                                      copyDelay: '',
                                      moveDelay: ''
                                    },
           name: container.name,
         };
}

function extractLocations(vContainer) {
  return [{ name: 'a cache',
            edit_name: 'cache',
            object: vContainer.cacheLocation
          },
          { name: 'an archive',
            edit_name: 'archive',
            object: vContainer.archiveLocation
          },
          { name: 'a migration target',
            edit_name: 'migration',
            object: vContainer.migrationTargetLocation
          }];
}

function setArchiveDuration(vContainer, toPrimary) {
  // HACK: We need to copy the copyDelay and moveDelay settings from the
  // archive location to the origin location (or vice versa) to present these
  // settings correctly on edits (and to save the edits correctly).
  var archive = vContainer.archiveLocation;
  if (archive.blobStoreId !== -1) {
    var primary = vContainer.originLocation;
    if (toPrimary) {
      primary.moveDelay = archive.moveDelay;
      primary.copyDelay = archive.copyDelay;
      archive.moveDelay = '';
      archive.copyDelay = '';
    } else {
      archive.moveDelay = primary.moveDelay;
      archive.copyDelay = primary.copyDelay;
    }
  }
  return;
}

storesControllers.controller('ViewStoresCtrl', ['$scope', '$location',
  '$interval', '$routeParams', 'ObjectStore', 'Container', 'VirtualContainer',
  'BounceService', function ($scope, $location, $interval, $routeParams,
      ObjectStore, Container, VirtualContainer, BounceService) {
    $scope.actions = {};
    $scope.locations = [];
    $scope.containersMap = {};
    $scope.refreshBounce = null;
    $scope.newContainer = null;
    $scope.providerLabel = null;

    $scope.getProviderLabel = function() {
      if ($scope.store.region === null) {
        return $scope.provider.name;
      } else {
        return $scope.provider.name + " (" +
          bounceConstants.getRegion($scope.provider, $scope.store.region).name +
          ")";
      }
    };

    $scope.refreshContainersMap = function() {
      for (var i = 0; i < $scope.stores.length; i++) {
        $scope.updateContainerMap($scope.stores[i].id);
      }
    };

    ObjectStore.query(function(results) {
      $scope.stores = results;
      var redirect = true;
      if ($routeParams.id !== null) {
        $scope.store = findStore($scope.stores, Number($routeParams.id));
        if ($scope.store !== undefined) {
          redirect = false;
        }
      }
      if (redirect === true) {
        if ($scope.stores.length > 0) {
          $location.path('/stores/' + $scope.stores[0].id);
        } else {
          $location.path('/create_store');
        }
      } else {
        $scope.provider = bounceConstants.getProvider($scope.store.provider);
        $scope.providerLabel = $scope.getProviderLabel();
        $scope.refreshContainersMap();
      }
    });

    $scope.updateContainerMap = function(blobStoreId) {
      $scope.containersMap[blobStoreId] = [];
      Container.query({ id: blobStoreId }, function(results) {
        for (var i = 0; i < results.length; i++) {
          $scope.containersMap[blobStoreId].push(results[i]);
        }
        if (blobStoreId === $scope.store.id) {
          $scope.containers = $scope.containersMap[$scope.store.id].filter(
            function(container) {
              return container.status !== 'INUSE';
            });
        }
      });
    };

    $scope.getContainersForPrompt = function() {
      if ($scope.editLocation === null || $scope.editLocation === undefined) {
        return [];
      }
      var editLocation = $scope.editLocation.object;
      var blobStoreId = editLocation.blobStoreId;
      if (!(blobStoreId in $scope.containersMap)) {
        console.log("blob store ID not found");
        return [];
      }
      return $scope.containersMap[blobStoreId].filter(
        function(container) {
          return (container.status === 'UNCONFIGURED' &&
                  container.name !== $scope.enhanceContainer.name) ||
                 (container.status === 'INUSE' &&
                  container.name === editLocation.containerName);
        });
    };

    $scope.actions.enhanceContainer = function(container) {
      if (container.status === 'UNCONFIGURED') {
        var vContainer = createNewVirtualContainer($scope.store, container);
        $scope.locations = extractLocations(vContainer);
        $scope.enhanceContainer = vContainer;
        $('#configureContainerModal').modal('show');
        return;
      }

      VirtualContainer.get({ id: container.virtualContainerId },
                           function(vContainer) {
                             $scope.enhanceContainer = vContainer;
                             setArchiveDuration(vContainer, false);
                             $scope.locations = extractLocations(vContainer);
                             $('#configureContainerModal').modal('show');
                           }
                          );
      return;
    };

    $scope.actions.prompt = function(locationObject) {
      $scope.editLocation = locationObject;
      $('#configureTierModal').modal('show');
    };

    $scope.actions.saveContainer = function() {
      setArchiveDuration($scope.enhanceContainer, true);
      if (typeof($scope.enhanceContainer.id) === 'undefined') {
        VirtualContainer.save($scope.enhanceContainer,
        function(result) {
          console.log('Saved container: ' + result.status);
          $scope.refreshContainersMap();
        },
        function(error) {
          console.log('Error: ' + error);
        });
      } else {
        VirtualContainer.update($scope.enhanceContainer,
        function(success) {
          console.log('Updated container: ' + success.status);
          $scope.refreshContainersMap();
        },
        function(error) {
          console.log('Error occurred during the update: ' + error);
        });
      }
      $('#configureContainerModal').modal('hide');
      $scope.enhanceContainer = null;
      $scope.editLocation = null;
    };

    $scope.actions.editStore = function(store) {
      $location.path('/edit_store/' + store.id);
    };

    $scope.interpretStatus = function(containerStatus) {
      if (containerStatus === 'UNCONFIGURED') {
        return 'passthrough';
      }
      if (containerStatus === 'CONFIGURED') {
        return 'enhanced';
      }
    };

    $scope.actions.bounce = function(container) {
      var $bounceBtn = $('#bounce-btn-' + container.name);
      $bounceBtn.addClass('disabled');
      BounceService.save({ name: container.name }, function(result) {
        $bounceBtn.html('Bouncing...');
        $bounceBtn.addClass('bouncing');
        if ($scope.refreshBounce === null) {
          $scope.refreshBounce = $interval(refreshBounceState, 1000);
        }
      },
      function(error) {
        console.log(error);
        $bounceBtn.removeClass('disabled');
      });
    };

    $scope.actions.addContainer = function() {
      Container.save({ id: $scope.store.id, name: $scope.newContainer },
        function(result) {
          $scope.updateContainerMap($scope.store.id);
        },
        function(error) {
          console.log(error);
        });
    };

    var refreshBounceState = function() {
      var $allBouncing = $('.bouncing');
      if ($allBouncing.length == 0) {
        $interval.cancel($scope.refreshBounce);
        $scope.refreshBounce = null;
        return;
      }
      for (var i = 0; i < $allBouncing.length; i++) {
        var $button = $allBouncing[i];
        var name = $button.id.substring("bounce-btn-".length);
        BounceService.get({ name: name }, function(result) {
          if (result.endTime !== null) {
            $('#bounce-btn-' + name).removeClass('disabled').removeClass('bouncing').html('bounce!');
          }
        }, function(error) {
          console.log(error);
        });
      }
    };

    $scope.$on('$locationChangeStart', function() {
      if ($scope.refreshBounce !== null) {
        $interval.cancel($scope.refreshBounce);
      }
    });
}]);
