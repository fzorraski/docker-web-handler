import {getDockerImages} from "../services/DockerImageService.js";

import {actionRemoveDockerImage} from "../controller/DockerImageController.js";

function showDockerImages(repository, tag, imageId, created, size) {

    const line = document.createElement('tr')

    line.innerHTML = `

        <td>${repository}</td>
        <td>${tag}</td>
        <td>${imageId}</td>
        <td>${created}</td>
        <td>${size}</td>
    `;
    line.appendChild(createButton('remove', imageId));
    return line;
}

function createButton(label, id) {

    let htmlButtonElement = document.createElement("button");
    let textNode = document.createTextNode(label);

    htmlButtonElement.id = "btn-i-" + label;

    htmlButtonElement.setAttribute('style', 'margin: 3px');
    htmlButtonElement.setAttribute('class', 'btn btn-danger');

    htmlButtonElement.appendChild(textNode);

    htmlButtonElement.addEventListener("click", () => {
        actionRemoveDockerImage(id)
    })

    return htmlButtonElement;
}

let bodyTable = document.querySelector('#dockerImagesTable');

getDockerImages().then(showImages => {
    showImages.forEach(index => {
        bodyTable.appendChild(showDockerImages(index.repository,
            index.tag,
            index.imageId,
            index.created,
            index.size
        ))
    })
})

// export function showLoader() {
//     document.getElementById('di-loader').style.display = "block";
//     document.getElementById('dockerImagesTable').style.display = "none";
// }

// export function stopLoader() {
//     document.getElementById('di-loader').style.display = "none";
//     document.getElementById('dockerImagesTable').style.display = "block";
// }

export function showLoader() {
    // let s = '<div class="spinner-border"></div>';
    $('.table-responsive').append('<div class="loader">' + '</div>');
    document.getElementById('dockerImagesTable').style.pointerEvents = "none";
}


// export function  newAlert(){
//     let s = '<div class="spinner-grow text-primary spinner-grow-sm mt-2"><span class="sr-only">Loading...</span></div>';
//
//     $('.table-responsive').append('<div class="loader text-center">' + s + '</div>');
// }


