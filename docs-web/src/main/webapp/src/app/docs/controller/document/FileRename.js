'use strict';

/**
 * File rename controller.
 */
angular.module('docs').controller('FileRename', function ($scope, file, $uibModalInstance,Restangular,$q,$translate) {
  $scope.file = file;
//  $scope.title = title;

  /**
   * Returns a promise for typeahead title.
   */
  $scope.getTitleTypeahead = function($viewValue) {
    var deferred = $q.defer();
    Restangular.one('document/list')
    .get({
      limit: 10,
      sort_column: 1,
      asc: true,
      search: $viewValue
    }).then(function(data) {
      deferred.resolve(_.uniq(data.documents), true);
    });
    return deferred.promise;
  };
  $scope.addDocument = function($item) {
    $scope.file.new_document_title = $item.title;
    $scope.file.new_document_id = $item.id;
  };
  
  $scope.close = function(file) {
    if( file==null) {
       $uibModalInstance.close(null);
       return;
    }    
    if( file.new_document_id===file.document_id ){
      if( file.new_document_title!=file.document_title ){
        file.error = $translate.instant('file.edit.document_name_error');
        return;
      }
    }
    $uibModalInstance.close(file);
  }
});