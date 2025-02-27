'use strict';

/**
 * Document view content controller.
 */
angular.module('docs').controller('DocumentViewContent', function ($scope, $rootScope, $stateParams, Restangular, $dialog, $state, Upload, $translate, $uibModal) {
  $scope.displayMode = _.isUndefined(localStorage.fileDisplayMode) ? 'grid' : localStorage.fileDisplayMode;
  $scope.openedFile = undefined;

  /**
   * Watch for display mode change.
   */
  $scope.$watch('displayMode', function (next) {
    localStorage.fileDisplayMode = next;
  });

  /**
   * Configuration for file sorting.
   */
  $scope.fileSortableOptions = {
    forceHelperSize: true,
    forcePlaceholderSize: true,
    tolerance: 'pointer',
    start: function() {
      $(this).addClass('currently-dragging');
    },
    stop: function () {
      var _this = this;
      setTimeout(function(){
        $(_this).removeClass('currently-dragging');
      }, 300);

      // Send new positions to server
      $scope.$apply(function () {
        Restangular.one('file').post('reorder', {
          id: $stateParams.id,
          order: _.pluck($scope.files, 'id')
        });
      });
    }
  };

  /**
   * Load files from server.
   */
  $scope.loadFiles = function () {
    Restangular.one('file/list').get({ id: $stateParams.id }).then(function (data) {
      $scope.files = data.files;
    });
  };
  $scope.loadFiles();

  /**
   * Returns a promise for typeahead title.
   */
  $scope.getTitleTypeahead = function($viewValue) {
    var deferred = $q.defer();
    Restangular.one('document/list')
    .get({
      limit: 5,
      sort_column: 1,
      asc: true,
      search: $viewValue
    }).then(function(data) {
      deferred.resolve(_.uniq(_.pluck(data.documents, 'title'), true));
    });
    return deferred.promise;
  };
  
  /**
   * Navigate to the selected file.
   */
  $scope.openFile = function (file, $event) {
    if ($($event.target).parents('.currently-dragging').length === 0) {
      $scope.openedFile = file;
      $state.go('document.view.content.file', { id: $stateParams.id, fileId: file.id });
    }
  };

  /**
   * Delete a file.
   */
  $scope.deleteFile = function (file) {
    var title = $translate.instant('document.view.content.delete_file_title');
    var msg = $translate.instant('document.view.content.delete_file_message');
    var btns = [
      {result: 'cancel', label: $translate.instant('cancel')},
      {result: 'ok', label: $translate.instant('ok'), cssClass: 'btn-primary'}
    ];

    $dialog.messageBox(title, msg, btns, function (result) {
      if (result === 'ok') {
        Restangular.one('file', file.id).remove().then(function () {
          // File deleted, decrease used quota
          $rootScope.userInfo.storage_current -= file.size;

          // Update local data
          $scope.viewDocument(file.document_id);
          $scope.loadDocuments();
          $scope.loadFiles();
        });
      }
    });
  };

  /**
   * Upload a new version.
   */
  $scope.uploadNewVersion = function (files, file) {
    if (!$scope.document.writable || !files || files.length === 0) {
      return;
    }

    var uploadedfile = files[0];
    var previousFileId = file.id;
    file.id = undefined;
    file.progress = 0;
    file.name = uploadedfile.name;
    file.create_date = new Date().getTime();
    file.mimetype = uploadedfile.type;
    file.version++;
    $scope.uploadFile(uploadedfile, file, previousFileId);
  };

  /**
   * File has been drag & dropped.
   */
  $scope.fileDropped = function (files) {
    if (!$scope.document.writable) {
      return;
    }

    if (files && files.length) {
      // Sort by filename
      files = _.sortBy(files, 'name');

      // Adding files to the UI
      var newfiles = [];
      _.each(files, function (file) {
        var newfile = {
          progress: 0,
          name: file.name,
          create_date: new Date().getTime(),
          mimetype: file.type,
          status: $translate.instant('document.view.content.upload_pending')
        };
        $scope.files.push(newfile);
        newfiles.push(newfile);
      });

      // Uploading files sequentially
      var key = 0;
      var then = function() {
        if (files[key]) {
          $scope.uploadFile(files[key], newfiles[key++]).then(then);
        }
      };
      then();
    }
  };

  /**
   * Upload a file.
   */
  $scope.uploadFile = function(file, newfile, previousFileId) {
    // Upload the file
    newfile.status = $translate.instant('document.view.content.upload_progress');
    return Upload.upload({
      method: 'PUT',
      url: '../api/file',
      file: file,
      fields: {
        id: $stateParams.id,
        previousFileId: previousFileId
      }
    })
    .progress(function(e) {
      newfile.progress = parseInt(100.0 * e.loaded / e.total);
    })
    .success(function(data) {
      // Update local model with real data
      newfile.id = data.id;
      newfile.size = data.size;

      // New file uploaded, increase used quota
      $rootScope.userInfo.storage_current += data.size;
      $scope.viewDocument(file.document_id);
      $scope.loadDocuments();
      $scope.loadFiles();

    })
    .error(function (data) {
      newfile.name = $translate.instant('document.default.upload_error_duplicate')
      newfile.status = $translate.instant('document.view.content.upload_error');
      if (data.type === 'QuotaReached') {
        newfile.status += ' - ' + $translate.instant('document.view.content.upload_error_quota');
      }else if (data.type=== 'FileNameDuplicate'){
        newfile.status += ' - ' + $translate.instant('document.default.upload_error_duplicate');
      }
    });
  };

  /**
   * Rename a file.
   */
  $scope.renameFile = function (file,title) {
    file.document_title = title;
    file.new_document_title = title;
    file.new_document_id = file.document_id;
    $uibModal.open({
      templateUrl: 'partial/docs/file.rename.html',
      controller: 'FileRename',
      resolve: {
        file: function () {
          return angular.copy(file);
        }
      }
    }).result.then(function (fileUpdated) {
      if (fileUpdated === null) {
        return;
      }
      if( fileUpdated.new_document_id===fileUpdated.document_id ){
        if( fileUpdated.new_document_title!=fileUpdated.document_title ){
          fileUpdated.new_document_title = $translate.instant('file.edit.document_name_error');
          return;
        }
      }
      // Rename the file
      Restangular.one('file/' + file.id).post('', {
        name: fileUpdated.name,
        document_id: fileUpdated.new_document_id
      }).then(function () {
        if( fileUpdated.new_document_id!=fileUpdated.document_id ){
          $scope.viewDocument(fileUpdated.new_document_id);
          $scope.loadDocuments();
          $scope.loadFiles();
//            $state.go('document.default');  
        }else
        file.name = fileUpdated.name;
      },function(error){
        file.name = $translate.instant('document.edit.rename_error');
      })
    });
  };

  /**
   * Process a file.
   */
  $scope.processFile = function (file) {
    Restangular.one('file/' + file.id).post('process').then(function () {
      file.processing = true;
    });
  };

  /**
   * Open versions history.
   */
  $scope.openVersions = function (file) {
    $uibModal.open({
      templateUrl: 'partial/docs/file.versions.html',
      controller: 'ModalFileVersions',
      size: 'lg',
      resolve: {
        file: function () {
          return file;
        }
      }
    })
  };
});