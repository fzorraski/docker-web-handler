import {handleStatus, requestInitPost} from "../utils/PromiseHelpers.js";
import {showLoader, stopLoader} from "../views/DockerImagesView.js";

const API = '/images/'


export function getDockerImages() {
    showLoader()

    return fetch(API + 'list')
        .then(res => handleStatus(res))
        .catch(err => {
            console.log(err);
            return Promise.reject('Could not get the list of docker images');
        }).finally(stopLoader);
}

export function removeDockerImage(imageId) {
    const payload = JSON.stringify({
        imageId: imageId
    })

    return fetch(API + 'remove', requestInitPost(payload))
        .then(res => handleStatus(res))
        .finally(() => location.reload())
}
