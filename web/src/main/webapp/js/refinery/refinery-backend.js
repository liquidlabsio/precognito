$(document).ready(function () {
    refineryBackendBinding()

});

class RefineryInterface {
    submit(search) {
    }
    status(sessionid) {
    }
    model(sessionid) {
    }
}

class RefineryRest extends RefineryInterface {

    searchToForm(search) {
        var formData = new FormData();
        formData.append("origin", JSON.stringify(search.origin));
        formData.append("uid", search.uid);
        formData.append("expression", JSON.stringify(search.expression));
        formData.append("from", search.from);
        formData.append("to", search.to);
        return formData;
    }

    submit(search, modelName) {
        $.Topic(Fluidity.Explorer.Topics.startSpinner).publish();
        jQuery.ajax({
            type: 'POST',
            url: SERVICE_URL + '/dataflow/submit/'
             + encodeURIComponent(DEFAULT_TENANT) + '/' +
             + encodeURIComponent(SERVICE_URL) + '/' +
             +  encodeURIComponent(modelname),
            contentType: 'application/json',
            data: JSON.stringify(search),
            dataType: 'json',
            success: function(response) {
                   $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
            // call on refinery
                console.log("Got Stuff");
           }
            ,
            fail: function (xhr, ajaxOptions, thrownError) {
                                   alert(xhr.status);
                                   alert(thrownError);
                                $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();

            }
        });
    }

    sleep(ms) {
      return new Promise(resolve => setTimeout(resolve, ms));
    }

    async searchFile(search, fileMetasArray) {
        searchingFilesCount++

        // throttle when too many outstanding requests
        if (searchingFilesCount > 100) {
            var waitPeriod = 800 + (searchingFilesCount * 2);
            console.log("Throttle Waiting:" + searchingFilesCount + ": wait:" + waitPeriod + " file:" + fileMetasArray[0].filename)
            await this.sleep(waitPeriod);
        }

        $.Topic(Fluidity.Explorer.Topics.startSpinner).publish();
        let self = this;
        let formData = this.searchToForm(search);

        jQuery.ajax({
            type: 'POST',
            url: SERVICE_URL + '/search/files/'
                + encodeURIComponent(DEFAULT_TENANT)
                + "/"+ encodeURIComponent( JSON.stringify(fileMetasArray)),
            contentType: 'multipart/form-data',
            data: self.searchToForm(search),
            processData: false,
            contentType: false,
            cache : false,
            success: function(response) {
                searchingFilesCount--;
               $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
               $.Topic(Fluidity.Search.Topics.setSearchFileResults).publish(response);
            },
            fail: function (xhr, ajaxOptions, thrownError) {
                searchingFilesCount--;
                console.log(xhr.status);
                console.log(thrownError);
                $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
            }
        });
    }
    getFinalEvents(search, fromTime) {
        $.Topic(Fluidity.Explorer.Topics.startSpinner).publish();
        let self = this;
        let formData = this.searchToForm(search);

        jQuery.ajax({
            type: 'POST',
            url: SERVICE_URL + '/search/finalizeEvents/'
                    + encodeURIComponent(DEFAULT_TENANT)
                    + "/"+ fromTime,
            contentType: 'multipart/form-data',
            data: self.searchToForm(search),
            processData: false,
            contentType: false,
            cache : false,
            success: function(response) {
                $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
                $.Topic(Fluidity.Search.Topics.setFinalEvents).publish(response);
            },
            fail: function (xhr, ajaxOptions, thrownError) {
                console.log(xhr.status);
                console.log(thrownError);
                $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
            }
        });
    }
    getFinalHisto(search) {
        $.Topic(Fluidity.Explorer.Topics.startSpinner).publish();
        let self = this;
        let formData = this.searchToForm(search);

        jQuery.ajax({
            type: 'POST',
            url: SERVICE_URL + '/search/finalizeHisto/'
                    + encodeURIComponent(DEFAULT_TENANT),
            contentType: 'multipart/form-data',
            data: self.searchToForm(search),
            processData: false,
            contentType: false,
            cache : false,
            success: function(response) {
                $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
                $.Topic(Fluidity.Search.Topics.setFinalHisto).publish(response);
            },
            fail: function (xhr, ajaxOptions, thrownError) {
                console.log(xhr.status);
                console.log(thrownError);
                $.Topic(Fluidity.Explorer.Topics.stopSpinner).publish();
            }
        });
    }


}



function refineryBackendBinding() {
//     let backend = new SearchFixture();
    let backend = new SearchRest();

    console.log("Backend is using:" + backend.constructor.name)

    $.Topic(Fluidity.Search.Topics.submitSearch).subscribe(function(search) {
        backend.submitSearch(search);
    })
    $.Topic(Fluidity.Search.Topics.searchFile).subscribe(function(search, files) {
        backend.searchFile(search, files);
    })
    $.Topic(Fluidity.Search.Topics.getFinalEvents).subscribe(function(search, fromTime) {
        backend.getFinalEvents(search, fromTime);
    })
    $.Topic(Fluidity.Search.Topics.getFinalHisto).subscribe(function(search) {
        backend.getFinalHisto(search);
    })


}